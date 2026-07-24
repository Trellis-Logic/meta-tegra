DESCRIPTION = "Automates the Jetson Orin secure boot PKC fuse-configuration flow \
and produces a self-contained tarball that burns the fuses on a device attached \
over USB with ./tegra-burnfuses --burn."
SUMMARY = "Generate a fuse-burning bundle for Jetson Orin secure boot"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

# Implements the flow from:
# https://docs.nvidia.com/jetson/archives/r36.5/DeveloperGuide/SD/Security/SecureBoot.html
# On r36 the fuse tool is odmfuse.sh (shipped in the Linux_for_Tegra BSP); the
# fskp_fuseburn.py host overlay used by r39 does not exist here.

COMPATIBLE_MACHINE = "(tegra)"
INHIBIT_DEFAULT_DEPS = "1"

# This recipe only ever produces a host-side deployment artifact (the fuse
# tarball); it installs nothing on the target. Build it on demand with
#     bitbake tegra-secure-boot-setup
# and pick up ${DEPLOY_DIR_IMAGE}/tegra-secure-boot-setup-${MACHINE}.tar.gz.
# It requires user-supplied keys, so keep it out of "bitbake world".
EXCLUDE_FROM_WORLD = "1"
PACKAGE_ARCH = "${MACHINE_ARCH}"

# tegra-flashtools-native provides tegrasign_v3.py (used to compute the PKC
# public-key hashes); openssl-native identifies each key's algorithm. odmfuse.sh
# itself comes from the full Linux_for_Tegra tree (unpacked by tegra-binaries)
# and is only run on the flashing host, so no host fuse tools are needed here.
DEPENDS = "tegra-flashtools-native openssl-native"

inherit tegra-ekb-fuse-key l4t_bsp deploy nopackages

# The self-contained bundle includes the full Linux_for_Tegra tree that
# odmfuse.sh needs; meta-tegra unpacks that tree once into
# L4T_BSP_SHARED_SOURCE_DIR via the tegra-binaries recipe.
do_compile[depends] += "tegra-binaries:do_preconfigure"

# ---------------------------------------------------------------------------
# User-provided inputs
# ---------------------------------------------------------------------------
#
# TEGRA_SIGNING_PKC is the PKC signing key (shared with image signing in
# image_types_tegra) whose public-key hash is burned into the PublicKeyHash
# fuse. TEGRA_SIGNING_PKC1/PKC2 are optional additional keys whose hashes go in
# PkcPubkeyHash1/2 (t234 only). All provided keys must use the SAME algorithm,
# which selects the authentication scheme (BootSecurityInfo bits [2:0]).
# r36 Orin supports:
#
#     3072-bit RSA:  openssl genrsa -out pkc.pem 3072
#     ECDSA P-256:   openssl ecparam -name prime256v1 -genkey -noout -out pkc.pem
#     ECDSA P-521:   openssl ecparam -name secp521r1  -genkey -noout -out pkc.pem
#
# Reference them from local.conf (or your distro.conf):
#
#     TEGRA_SIGNING_PKC  = "/path/to/pkc.pem"
#     TEGRA_SIGNING_PKC1 = "/path/to/pkc_1.pem"   # optional
#     TEGRA_SIGNING_PKC2 = "/path/to/pkc_2.pem"   # optional
#
# TEGRA_SIGNING_PKC is defined globally by image_types_tegra; declare weak
# defaults here so the recipe parses when it is not inherited.
TEGRA_SIGNING_PKC  ??= ""
TEGRA_SIGNING_PKC1 ?= ""
TEGRA_SIGNING_PKC2 ?= ""

# TEGRA_SIGNING_SBK is the (optional) Secure Boot Key file for boot-image
# encryption. When unset, no encryption is configured or supported.
TEGRA_SIGNING_SBK ??= ""

# ---------------------------------------------------------------------------
# fuse.xml values (see the r36.5 secure boot guide)
# ---------------------------------------------------------------------------
# Base BootSecurityInfo; bits [2:0] (authentication scheme) are set at build
# time from the PKC key algorithm (0x1 = RSA-3K, 0x2 = ECDSA P-256, 0x3 = ECDSA
# P-521). Leave the upper bits 0 for the standard Orin configuration.
TEGRA_FUSE_BOOTSECURITYINFO ?= "0x0"
# SecurityMode = 0x1 permanently enables secure boot (locks further fuse writes).
TEGRA_FUSE_SECURITYMODE     ?= "0x1"

# Name of the fuse that stores the OEM/disk-encryption key (TEGRA_EKB_FUSE_KEY).
TEGRA_FUSE_OEMKEY_NAME          ?= "OemK1"

# ---------------------------------------------------------------------------
# odmfuse.sh parameters
# ---------------------------------------------------------------------------
# Tegra chip id passed via -i (0x23 for Orin/t234).
TEGRA_FUSEBURN_CHIPID ?= "${NVIDIA_CHIP}"
# odmfuse.sh TargetBoard: the <name>.conf at the Linux_for_Tegra root. For the
# Jetson devkits the NVIDIA .conf name matches the meta-tegra MACHINE.
TEGRA_FUSEBURN_TARGET ?= "${MACHINE}"

TEGRA_SECUREBOOT_TARBALL = "tegra-secure-boot-setup-${MACHINE}.tar.gz"

# Path to tegrasign_v3.py in the native sysroot (installed by tegra-flashtools).
TEGRASIGN = "${STAGING_BINDIR_NATIVE}/tegra-flash/tegrasign_v3.py"
# The full Linux_for_Tegra BSP tree (shared, unpacked by tegra-binaries).
L4T_SRC_DIR = "${L4T_BSP_SHARED_SOURCE_DIR}"
# openssl from openssl-native, used to identify each PKC key's algorithm.
OPENSSL_BIN = "${STAGING_BINDIR_NATIVE}/openssl"

do_configure[noexec] = "1"

# Build the file-checksums entry from only the key files that are set (and whose
# contents affect the output). Emitting bare "${VAR}:True" for unset keys yields
# an empty path that the checksum machinery tries to stat as ".", warning.
def tegra_signing_filechecksums(d):
    entries = []
    for var in ("TEGRA_SIGNING_PKC", "TEGRA_SIGNING_PKC1", "TEGRA_SIGNING_PKC2",
                "TEGRA_EKB_FUSE_KEY", "TEGRA_SIGNING_SBK"):
        path = d.getVar(var)
        if path:
            entries.append(path + ":True")
    return " ".join(entries)

# Shared help text for generating a 256-bit symmetric key file (OEM/EKB key and
# Secure Boot Key), per the Key Preparation guide.
def tegra_symmetric_key_help(d):
    return (
        "\nGenerate a 256-bit key and write it to a file as eight big-endian\n"
        "32-bit words (see the Key Preparation guide:\n"
        "https://docs.nvidia.com/jetson/archives/r36.5/DeveloperGuide/SD/Security/SecureBoot.html):\n\n"
        "    # 256-bit (32-byte) random key, as eight 32-bit big-endian words\n"
        "    hex=$(openssl rand -hex 32)\n"
        "    for ((i=0; i<64; i+=8)); do printf '0x%s ' \"${hex:$i:8}\"; done > key.txt\n"
    )

# ---------------------------------------------------------------------------
# Prerequisite checks with actionable error messages.
# ---------------------------------------------------------------------------
python do_check_prereqs() {
    import os

    keygen_help = (
        "\nSecure boot requires a PKC signing key (TEGRA_SIGNING_PKC). Generate one\n"
        "with one of (all keys must use the same algorithm):\n\n"
        "    3072-bit RSA:  openssl genrsa -out pkc.pem 3072\n"
        "    ECDSA P-256:   openssl ecparam -name prime256v1 -genkey -noout -out pkc.pem\n"
        "    ECDSA P-521:   openssl ecparam -name secp521r1  -genkey -noout -out pkc.pem\n\n"
        "then reference it from local.conf (or your distro.conf):\n\n"
        '    TEGRA_SIGNING_PKC = "/path/to/pkc.pem"\n'
    )

    sym_key_help = tegra_symmetric_key_help(d)

    missing = []
    pkc = d.getVar("TEGRA_SIGNING_PKC")
    if not pkc:
        missing.append("TEGRA_SIGNING_PKC is not set")
    elif not os.path.exists(pkc):
        missing.append("TEGRA_SIGNING_PKC points at a missing file: %s" % pkc)
    # PKC1/PKC2 are optional, but if set they must exist.
    for var in ("TEGRA_SIGNING_PKC1", "TEGRA_SIGNING_PKC2"):
        path = d.getVar(var)
        if path and not os.path.exists(path):
            missing.append("%s points at a missing file: %s" % (var, path))

    if not d.getVar("TEGRA_EKB_FUSE_KEY"):
        bb.warn("TEGRA_EKB_FUSE_KEY is not set. This key is programmed into the "
                "OEM key fuse and is REQUIRED when using disk encryption. If you "
                "intend to use encrypted storage, set TEGRA_EKB_OEM_K1 (t234)"
                "to a key file so it matches the key "
                "baked into eks.img by tegra-eks-image." + sym_key_help)

    if not d.getVar("TEGRA_SIGNING_SBK"):
        bb.warn("TEGRA_SIGNING_SBK is not set. Without a Secure Boot Key, no boot "
                "image encryption will be performed or supported. Set "
                "TEGRA_SIGNING_SBK to an SBK key file to enable encryption."
                + sym_key_help)

    if missing:
        bb.fatal("tegra-secure-boot-setup is missing required PKC keys:\n  - "
                 + "\n  - ".join(missing) + "\n" + keygen_help)
}
addtask check_prereqs before do_compile after do_configure
do_check_prereqs[file-checksums] = "${@tegra_signing_filechecksums(d)}"

# ---------------------------------------------------------------------------
# Resolve BootSecurityInfo (the secure-boot authentication scheme fuse).
#
# The fusing procedure is SoC-specific. Only t234 (Jetson Orin) is implemented
# here; other SoCs follow a different procedure, so the default function errors
# out and t234 supplies an override that sets TEGRA_BOOTSECURITYINFO_RESOLVED
# and TEGRA_SECUREBOOT_KEY_SCHEME.
# ---------------------------------------------------------------------------
python tegra_resolve_bootsecurityinfo() {
    bb.fatal("tegra-secure-boot-setup: secure boot fuse programming is not "
             "implemented for MACHINE=%s (SoC=%s). Only t234 (Jetson Orin) is "
             "supported." % (d.getVar("MACHINE"), d.getVar("SOC_FAMILY") or "unknown"))
}

python tegra_resolve_bootsecurityinfo:tegra234() {
    import re
    import subprocess

    # BootSecurityInfo bits [2:0] select the authentication scheme and must
    # match the PKC key algorithm:
    #   001b 3072-bit RSA, 010b ECDSA P-256, 011b ECDSA P-521
    openssl = d.getVar("OPENSSL_BIN")
    SCHEME_BITS = {
        "3072-bit RSA": 0b001,
        "ECDSA P-256":  0b010,
        "ECDSA P-521":  0b011,
    }

    def detect_scheme(pem):
        txt = None
        for extra in ([], ["-pubin"]):
            p = subprocess.run([openssl, "pkey"] + extra + ["-in", pem, "-noout", "-text"],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if p.returncode == 0:
                txt = p.stdout.decode("utf-8", "replace").lower()
                break
        if txt is None:
            bb.fatal("Could not parse %s with openssl (expected a PEM private or "
                     "public key)." % pem)
        if "p-256" in txt or "prime256v1" in txt or "secp256r1" in txt:
            return "ECDSA P-256"
        if "p-521" in txt or "secp521r1" in txt:
            return "ECDSA P-521"
        if "modulus" in txt:  # RSA
            m = re.search(r"(?:private|public)-key:\s*\((\d+)\s*bit", txt)
            bits = int(m.group(1)) if m else 0
            if bits != 3072:
                bb.fatal("%s is RSA-%d, but secure boot requires 3072-bit RSA "
                         "(or ECDSA P-256 or P-521)." % (pem, bits))
            return "3072-bit RSA"
        bb.fatal("%s is not a supported secure-boot key. Supported: 3072-bit RSA, "
                 "ECDSA P-256, ECDSA P-521." % pem)

    pkc_vars = [v for v in ("TEGRA_SIGNING_PKC", "TEGRA_SIGNING_PKC1", "TEGRA_SIGNING_PKC2")
                if d.getVar(v)]
    schemes = {v: detect_scheme(d.getVar(v)) for v in pkc_vars}
    if len(set(schemes.values())) != 1:
        bb.fatal("All PKC keys must use the same authentication scheme, but got: %s"
                 % ", ".join("%s=%s" % (v, s) for v, s in schemes.items()))
    scheme = next(iter(schemes.values()))

    base_bsi = int(d.getVar("TEGRA_FUSE_BOOTSECURITYINFO") or "0x0", 16)
    d.setVar("TEGRA_BOOTSECURITYINFO_RESOLVED",
             "0x%x" % ((base_bsi & ~0b111) | SCHEME_BITS[scheme]))
    d.setVar("TEGRA_SECUREBOOT_KEY_SCHEME", scheme)
}

# ---------------------------------------------------------------------------
# Build the bundle: compute PKC hashes, render fuse.xml, validate it offline,
# and assemble the tarball with the tegra-burnfuses wrapper around odmfuse.sh.
# ---------------------------------------------------------------------------
python do_compile() {
    import os
    import re
    import shutil
    import subprocess
    import tarfile

    b = d.getVar("B")
    tegrasign = d.getVar("TEGRASIGN")
    tegrasign_dir = os.path.dirname(tegrasign)

    # Resolve BootSecurityInfo first (SoC-specific; only t234 implemented).
    bb.build.exec_func("tegra_resolve_bootsecurityinfo", d)
    boot_security_info = d.getVar("TEGRA_BOOTSECURITYINFO_RESOLVED")
    bb.note("PKC key algorithm: %s -> BootSecurityInfo=%s"
            % (d.getVar("TEGRA_SECUREBOOT_KEY_SCHEME"), boot_security_info))

    def pubkeyhash(pem):
        base = os.path.basename(pem)
        pub_out = os.path.join(b, base + ".pubkey")
        hash_out = os.path.join(b, base + ".hash")
        cmd = ["python3", tegrasign, "--pubkeyhash", pub_out, hash_out, "--key", pem]
        bb.note("Running: %s" % " ".join(cmd))
        try:
            # Run from tegrasign's own directory so its helper imports resolve.
            out = subprocess.check_output(cmd, stderr=subprocess.STDOUT,
                                          cwd=tegrasign_dir).decode("utf-8", "replace")
        except subprocess.CalledProcessError as e:
            bb.fatal("tegrasign_v3.py failed for %s:\n%s"
                     % (pem, e.output.decode("utf-8", "replace")))
        m = re.search(r"tegra-fuse format \(big-endian\):\s*(0x[0-9a-fA-F]+)", out)
        if not m:
            bb.fatal("Could not parse the tegra-fuse (big-endian) hash from "
                     "tegrasign_v3.py output for %s:\n%s" % (pem, out))
        return m.group(1)

    # Read a symmetric-key file (256-bit key as eight big-endian 32-bit words)
    # into a fuse value. Returns None when the file is unset/absent/empty or
    # all-zero (i.e. "no key").
    def read_fuse_key(varname):
        path = d.getVar(varname)
        if not (path and os.path.exists(path) and os.path.getsize(path) > 0):
            return None
        with open(path) as f:
            toks = f.read().split()
        hexstr = "".join(t[2:] if t[:2].lower() == "0x" else t for t in toks)
        try:
            int(hexstr, 16)
        except ValueError:
            bb.fatal("%s file %s is not hexadecimal (expected eight 0x-prefixed "
                     "32-bit words)." % (varname, path))
        if len(hexstr) != 64:
            bb.fatal("%s file %s must hold a 256-bit key (64 hex digits / eight "
                     "32-bit words), got %d hex digits." % (varname, path, len(hexstr)))
        return None if set(hexstr) == {"0"} else "0x" + hexstr.lower()

    # ---- Render fuse.xml -------------------------------------------------
    fuses = [('PublicKeyHash', 64, pubkeyhash(d.getVar("TEGRA_SIGNING_PKC")))]
    if d.getVar("TEGRA_SIGNING_PKC1"):
        fuses.append(('PkcPubkeyHash1', 64, pubkeyhash(d.getVar("TEGRA_SIGNING_PKC1"))))
    if d.getVar("TEGRA_SIGNING_PKC2"):
        fuses.append(('PkcPubkeyHash2', 64, pubkeyhash(d.getVar("TEGRA_SIGNING_PKC2"))))

    # OEM/disk-encryption key (OemK1 on t234).
    oem_val = read_fuse_key("TEGRA_EKB_FUSE_KEY")
    if oem_val:
        fuses.append((d.getVar("TEGRA_FUSE_OEMKEY_NAME"), 32, oem_val))

    # Secure Boot Key enables boot-image encryption.
    sbk_val = read_fuse_key("TEGRA_SIGNING_SBK")
    if sbk_val:
        fuses.append(("SecureBootKey", 32, sbk_val))

    fuses.append(('BootSecurityInfo', 4, boot_security_info))
    # SecurityMode is applied last: it locks the fuses.
    fuses.append(('SecurityMode', 4, d.getVar("TEGRA_FUSE_SECURITYMODE")))

    lines = ['<genericfuse MagicId="0x45535546" version="1.0.0">']
    for name, size, value in fuses:
        lines.append('   <fuse name="%s" size="%d" value="%s"/>' % (name, size, value))
    lines.append('</genericfuse>')
    fuse_xml = "\n".join(lines) + "\n"

    # ---- Assemble the self-contained bundle ------------------------------
    # odmfuse.sh must run inside a full Linux_for_Tegra tree.
    bundle = os.path.join(b, "bundle")
    if os.path.exists(bundle):
        shutil.rmtree(bundle)
    os.makedirs(bundle)
    l4t_dst = os.path.join(bundle, "Linux_for_Tegra")

    l4t_src = d.getVar("L4T_SRC_DIR")
    if not os.path.isdir(l4t_src):
        bb.fatal("Full Linux_for_Tegra tree not found at %s (it should be unpacked "
                 "by the tegra-binaries recipe)." % l4t_src)
    if not os.path.exists(os.path.join(l4t_src, "odmfuse.sh")):
        bb.fatal("odmfuse.sh not found in %s; the r36 BSP layout may have changed." % l4t_src)
    bb.note("Copying Linux_for_Tegra from %s (this is large)..." % l4t_src)
    shutil.copytree(l4t_src, l4t_dst, symlinks=True)

    fuse_xml_name = "tegra-secure-boot-fuse.xml"
    with open(os.path.join(l4t_dst, fuse_xml_name), "w") as f:
        f.write(fuse_xml)

    chipid = d.getVar("TEGRA_FUSEBURN_CHIPID") or ""
    target = d.getVar("TEGRA_FUSEBURN_TARGET") or ""
    if not os.path.exists(os.path.join(l4t_dst, target + ".conf")):
        avail = sorted(f[:-5] for f in os.listdir(l4t_dst) if f.endswith(".conf"))
        bb.fatal("odmfuse target board '%s' (%s.conf) not found at the "
                 "Linux_for_Tegra root. Set TEGRA_FUSEBURN_TARGET to one of:\n  %s"
                 % (target, target, "\n  ".join(avail)))

    # Note: odmfuse.sh is only run on the flashing host (via tegra-burnfuses),
    # not at build time -- the r36 secure boot guide does not document an
    # offline/host-side odmfuse invocation.

    # ---- The tegra-burnfuses wrapper (run on the flashing host) -----------
    script = (
        "#!/bin/sh\n"
        "# Burn (or dry-run) the Jetson secure boot fuses on a device attached\n"
        "# over USB in recovery mode. Generated by tegra-secure-boot-setup.\n"
        "#\n"
        "#   ./tegra-burnfuses            dry-run (odmfuse.sh --test)\n"
        "#   ./tegra-burnfuses --burn     WARNING: irreversibly burns the fuses\n"
        "set -e\n"
        'here="$(cd "$(dirname "$0")" && pwd)"\n'
        'MODE="test"\n'
        'case "$1" in\n'
        '  --burn) MODE="burn" ;;\n'
        '  --test|"") MODE="test" ;;\n'
        '  *) echo "usage: $0 [--burn|--test]" >&2; exit 1 ;;\n'
        'esac\n'
        'CHIPID="%s"\n'
        'TARGET="%s"\n'
        'FUSE_XML="%s"\n'
        'if [ "$MODE" = "burn" ]; then\n'
        '  echo "*** About to IRREVERSIBLY burn fuses. Press Ctrl-C to abort. ***"\n'
        'fi\n'
        'cd "$here/Linux_for_Tegra"\n'
        'set -- ./odmfuse.sh -i "$CHIPID" -X "$FUSE_XML"\n'
        '[ "$MODE" = "test" ] && set -- "$@" --test\n'
        'set -- "$@" "$TARGET"\n'
        'echo "Running: sudo $*"\n'
        'exec sudo "$@"\n'
    ) % (chipid, target, fuse_xml_name)

    script_path = os.path.join(bundle, "tegra-burnfuses")
    with open(script_path, "w") as f:
        f.write(script)
    os.chmod(script_path, 0o755)

    readme = (
        "Jetson secure boot fuse bundle\n"
        "==============================\n\n"
        "Contents:\n"
        "  Linux_for_Tegra/  - full NVIDIA BSP; the generated fuse config is at\n"
        "                      Linux_for_Tegra/%s and is burned with odmfuse.sh\n"
        "  tegra-burnfuses   - wrapper to burn/dry-run the fuses\n\n"
        "Usage:\n"
        "  1. Put the target board into Force Recovery mode and attach it over USB.\n"
        "  2. Dry-run first:   ./tegra-burnfuses\n"
        "  3. Burn (final!):   ./tegra-burnfuses --burn\n\n"
        "WARNING: burning fuses is irreversible and permanently modifies the board.\n"
        % fuse_xml_name
    )
    with open(os.path.join(bundle, "README.txt"), "w") as f:
        f.write(readme)

    # ---- Pack the tarball (large: contains the full Linux_for_Tegra) -----
    tarball = os.path.join(b, d.getVar("TEGRA_SECUREBOOT_TARBALL"))
    with tarfile.open(tarball, "w:gz") as tar:
        tar.add(bundle, arcname="tegra-secure-boot-setup")
}
do_compile[file-checksums] = "${@tegra_signing_filechecksums(d)}"
# BootSecurityInfo / the fuse set depend on the key presence and PKC algorithms;
# make sure those inputs are part of this task's signature.
do_compile[vardeps] += "TEGRA_SIGNING_PKC TEGRA_SIGNING_PKC1 TEGRA_SIGNING_PKC2 TEGRA_SIGNING_SBK TEGRA_FUSE_BOOTSECURITYINFO TEGRA_FUSE_SECURITYMODE"

do_deploy() {
    install -D -m 0644 ${B}/${TEGRA_SECUREBOOT_TARBALL} ${DEPLOYDIR}/${TEGRA_SECUREBOOT_TARBALL}
}
addtask deploy after do_compile before do_build

# Tell the user where the artifact landed and how to use it.
python tegra_secureboot_usage_note() {
    import os
    tarball = os.path.join(d.getVar("DEPLOY_DIR_IMAGE"), d.getVar("TEGRA_SECUREBOOT_TARBALL"))
    bb.plain(
        "\n"
        "tegra-secure-boot-setup: fuse bundle deployed to\n"
        "    %s\n"
        "\n"
        "To program the secure boot fuses:\n"
        "  1. Copy the tarball to a host with USB access to the target and extract it:\n"
        "       tar xzf %s\n"
        "       cd tegra-secure-boot-setup\n"
        "  2. Put the Jetson into Force Recovery mode and connect it over USB.\n"
        "  3. Dry-run first (nothing is burned):\n"
        "       ./tegra-burnfuses\n"
        "  4. Burn the fuses (IRREVERSIBLE):\n"
        "       ./tegra-burnfuses --burn\n"
        % (tarball, os.path.basename(tarball)))
}
do_deploy[postfuncs] += "tegra_secureboot_usage_note"
