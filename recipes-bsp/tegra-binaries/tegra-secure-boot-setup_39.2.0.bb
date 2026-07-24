DESCRIPTION = "Automates the Jetson Orin secure boot PKC fuse-configuration flow \
and produces a self-contained tarball that burns the fuses on a device attached \
over USB with ./tegra-burnfuses --burn."
SUMMARY = "Generate a fuse-burning bundle for Jetson Orin secure boot"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

# Implements the flow from:
# https://docs.nvidia.com/jetson/archives/r39.2/DeveloperGuide/SD/Security/SecureBoot/QuickStartOrin.html

COMPATIBLE_MACHINE = "(tegra)"
INHIBIT_DEFAULT_DEPS = "1"

# This recipe only ever produces a host-side deployment artifact (the fuse
# tarball); it installs nothing on the target. Build it on demand with
#     bitbake tegra-secure-boot-setup
# and pick up ${DEPLOY_DIR_IMAGE}/tegra-secure-boot-setup-${MACHINE}.tar.gz.
# It is machine-specific (fuse names/chip id depend on the SoC) but does not
# belong in any image, and requires user-supplied keys, so keep it out of
# "bitbake world".
EXCLUDE_FROM_WORLD = "1"
PACKAGE_ARCH = "${MACHINE_ARCH}"

# tegra-flashtools-native provides tegrasign_v3.py (used to compute the PKC
# public-key hashes); fskp-tools-native provides fskp_fuseburn.py; openssl-native
# is used to identify each key's algorithm. All are host tools, pulled in as
# native dependencies.
DEPENDS = "tegra-flashtools-native fskp-tools-native openssl-native"

inherit tegra-ekb-fuse-key tegra-boardvars l4t_bsp deploy nopackages

# The self-contained bundle includes the full Linux_for_Tegra tree that
# fskp_fuseburn needs (bootloader configs, firmware, device tools). meta-tegra
# unpacks that tree once into L4T_BSP_SHARED_SOURCE_DIR via the tegra-binaries
# recipe; depend on its preconfigure step so the tree is present.
do_compile[depends] += "tegra-binaries:do_preconfigure"

# ---------------------------------------------------------------------------
# User-provided inputs
# ---------------------------------------------------------------------------
#
# Three PKC key pairs are required. NVIDIA recommends generating three because
# the key hashes can only be burned once, and having spares lets you rotate the
# active signing key later. TEGRA_SIGNING_PKC is the primary signing key (shared
# with image signing in image_types_tegra); TEGRA_SIGNING_PKC1/PKC2 are spares.
# All three must use the SAME algorithm, which selects the secure-boot
# authentication scheme (BootSecurityInfo bits [2:0]). Supported algorithms and
# how to generate a key:
#
#     3072-bit RSA:  openssl genrsa -out pkc.pem 3072
#     ECDSA P-256:   openssl ecparam -name prime256v1 -genkey -noout -out pkc.pem
#     ECDSA P-521:   openssl ecparam -name secp521r1  -genkey -noout -out pkc.pem
#     Ed25519:       openssl genpkey -algorithm ed25519 -out pkc.pem
#
# Point these variables at PEM files kept outside the build tree (in local.conf
# or your distro.conf):
#
#     TEGRA_SIGNING_PKC  = "/path/to/pkc.pem"
#     TEGRA_SIGNING_PKC1 = "/path/to/pkc_1.pem"
#     TEGRA_SIGNING_PKC2 = "/path/to/pkc_2.pem"
#
# TEGRA_SIGNING_PKC is defined globally by image_types_tegra; declare weak
# defaults here so the recipe parses when it is not inherited.
TEGRA_SIGNING_PKC  ??= ""
TEGRA_SIGNING_PKC1 ?= ""
TEGRA_SIGNING_PKC2 ?= ""

# TEGRA_SIGNING_SBK is the (optional) Secure Boot Key file used for boot-image
# encryption. When unset, no encryption is configured or supported.
TEGRA_SIGNING_SBK ??= ""

# ---------------------------------------------------------------------------
# fuse.xml values (defaults per the Orin secure boot QuickStart guide)
# ---------------------------------------------------------------------------
TEGRA_FUSE_PSCODMSTATIC     ?= "0x00000060"
TEGRA_FUSE_OPTINENABLE      ?= "0x1"
# Bits [2:0] (the authentication scheme) are set from the PKC key algorithm, and
# bit 3 (encryption enabled) is set only when TEGRA_SIGNING_SBK is provided; the
# remaining bits come from this value.
TEGRA_FUSE_BOOTSECURITYINFO ?= "0x2be1"
TEGRA_FUSE_SECURITYMODE     ?= "0x1"

# Name of the fuse that stores the OEM/disk-encryption key (TEGRA_EKB_FUSE_KEY).
TEGRA_FUSE_OEMKEY_NAME          ?= "OemK1"
TEGRA_FUSE_OEMKEY_NAME:tegra264 ?= "PscOemKdk1"

# ---------------------------------------------------------------------------
# fskp_fuseburn parameters (see "Fuse the Board" in the guide)
# ---------------------------------------------------------------------------
# Chip id passed via -c. Defaults to the machine's chip (0x23 for t234).
TEGRA_FUSEBURN_CHIPID ?= "${NVIDIA_CHIP}"
# The --board-spec file is generated at build time from the machine's flash
# variables (rather than shipped as a fixed file), so it always matches the
# configured MACHINE:
#     BOARDID    <- TEGRA_BOARDID
#     BOARDSKU   <- TEGRA_BOARDSKU
#     FAB        <- TEGRA_FAB
#     CHIP_SKU   <- TEGRA_FLASHVAR_CHIP_SKU
#     RAMCODE_ID <- TEGRA_FLASHVAR_RAMCODE
#     FUSELEVEL  <- TEGRA_FUSEBURN_FUSELEVEL (below)
TEGRA_FUSEBURN_FUSELEVEL ?= "fuselevel_production"
# Board configuration file passed to fskp_fuseburn via -B (mandatory for t234).
# It lives at the root of the bundled Linux_for_Tegra tree; for the Jetson
# devkits the NVIDIA .conf name matches the meta-tegra MACHINE. Override if your
# board uses a differently-named .conf.
TEGRA_FUSEBURN_BOARD_CONF ?= "${MACHINE}.conf"

TEGRA_SECUREBOOT_TARBALL = "tegra-secure-boot-setup-${MACHINE}.tar.gz"

# Path to tegrasign_v3.py in the native sysroot (installed by tegra-flashtools).
TEGRASIGN = "${STAGING_BINDIR_NATIVE}/tegra-flash/tegrasign_v3.py"
# Staged FSKP tools tree in the native sysroot (installed by fskp-tools). This
# is the "host overlay" that gets merged onto the full Linux_for_Tegra tree.
FSKP_STAGED_DIR = "${STAGING_DATADIR_NATIVE}/tegra-fskp"
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
        "https://docs.nvidia.com/jetson/archives/r39.2/DeveloperGuide/SD/Security/SecureBoot/KeyPreparation.html)\n"
        "See commands below for reference, ideally use an HSM for this to get truly random output:\n\n"
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
        "\nSecure boot requires three PKC key pairs, all using the same algorithm.\n"
        "Generate each with one of (RSA shown; pick a single algorithm):\n\n"
        "    3072-bit RSA:  openssl genrsa -out pkc.pem 3072\n"
        "    ECDSA P-256:   openssl ecparam -name prime256v1 -genkey -noout -out pkc.pem\n"
        "    ECDSA P-521:   openssl ecparam -name secp521r1  -genkey -noout -out pkc.pem\n"
        "    Ed25519:       openssl genpkey -algorithm ed25519 -out pkc.pem\n\n"
        "then reference them from local.conf (or your distro.conf):\n\n"
        '    TEGRA_SIGNING_PKC  = "/path/to/pkc.pem"\n'
        '    TEGRA_SIGNING_PKC1 = "/path/to/pkc_1.pem"\n'
        '    TEGRA_SIGNING_PKC2 = "/path/to/pkc_2.pem"\n'
    )

    sym_key_help = tegra_symmetric_key_help(d)

    missing = []
    for var in ("TEGRA_SIGNING_PKC", "TEGRA_SIGNING_PKC1", "TEGRA_SIGNING_PKC2"):
        path = d.getVar(var)
        if not path:
            missing.append("%s is not set" % var)
        elif not os.path.exists(path):
            missing.append("%s points at a missing file: %s" % (var, path))

    if not d.getVar("TEGRA_EKB_FUSE_KEY"):
        bb.warn("TEGRA_EKB_FUSE_KEY is not set. This key is programmed into the "
                "OEM key fuse and is REQUIRED when using disk encryption. If you "
                "intend to use encrypted storage, set TEGRA_EKB_OEM_K1 (t234) or "
                "TEGRA_EKB_OEM_KDK1 (t264) to a key file so it matches the key "
                "baked into eks.img by tegra-eks-image." + sym_key_help)

    if not d.getVar("TEGRA_SIGNING_SBK"):
        bb.warn("TEGRA_SIGNING_SBK is not set. Without a Secure Boot Key, no boot "
                "image encryption will be performed or supported (BootSecurityInfo "
                "bit 3 stays cleared). Set TEGRA_SIGNING_SBK to an SBK key file to "
                "enable encryption." + sym_key_help)

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
# here; Thor and other SoCs follow a completely different procedure -- see
# https://docs.nvidia.com/jetson/archives/r39.2/DeveloperGuide/SD/Security/SecureBoot/QuickStartThor.html
# so the default function errors out and t234 supplies an override.
#
# The override sets TEGRA_BOOTSECURITYINFO_RESOLVED and TEGRA_SECUREBOOT_KEY_SCHEME.
# ---------------------------------------------------------------------------
python tegra_resolve_bootsecurityinfo() {
    bb.fatal("tegra-secure-boot-setup: secure boot fuse programming is not "
             "implemented for MACHINE=%s (SoC=%s). Only t234 (Jetson Orin) is "
             "supported; the Thor / other-SoC procedure is different -- see "
             "https://docs.nvidia.com/jetson/archives/r39.2/DeveloperGuide/SD/"
             "Security/SecureBoot/QuickStartThor.html"
             % (d.getVar("MACHINE"), d.getVar("SOC_FAMILY") or "unknown"))
}

python tegra_resolve_bootsecurityinfo:tegra234() {
    import re
    import subprocess

    # BootSecurityInfo bits:
    #   [2:0] authentication scheme, from the PKC key algorithm:
    #         001b 3072-bit RSA, 010b ECDSA P-256, 011b ECDSA P-521, 100b Ed25519
    #         (000b SHA2-512 and 101b XMSS are intentionally not offered here.)
    #   [3]   encryption enabled, set only when an SBK (TEGRA_SIGNING_SBK) is given.
    openssl = d.getVar("OPENSSL_BIN")
    SCHEME_BITS = {
        "3072-bit RSA": 0b001,
        "ECDSA P-256":  0b010,
        "ECDSA P-521":  0b011,
        "Ed25519":      0b100,
    }

    def detect_scheme(pem):
        txt = None
        # Accept either a private key or a bare public key.
        for extra in ([], ["-pubin"]):
            p = subprocess.run([openssl, "pkey"] + extra + ["-in", pem, "-noout", "-text"],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if p.returncode == 0:
                txt = p.stdout.decode("utf-8", "replace").lower()
                break
        if txt is None:
            bb.fatal("Could not parse %s with openssl (expected a PEM private or "
                     "public key)." % pem)
        if "ed25519" in txt:
            return "Ed25519"
        if "p-256" in txt or "prime256v1" in txt or "secp256r1" in txt:
            return "ECDSA P-256"
        if "p-521" in txt or "secp521r1" in txt:
            return "ECDSA P-521"
        if "modulus" in txt:  # RSA
            m = re.search(r"(?:private|public)-key:\s*\((\d+)\s*bit", txt)
            bits = int(m.group(1)) if m else 0
            if bits != 3072:
                bb.fatal("%s is RSA-%d, but secure boot requires 3072-bit RSA "
                         "(or ECDSA P-256, ECDSA P-521, or Ed25519)." % (pem, bits))
            return "3072-bit RSA"
        bb.fatal("%s is not a supported secure-boot key. Supported: 3072-bit RSA, "
                 "ECDSA P-256, ECDSA P-521, Ed25519." % pem)

    schemes = {var: detect_scheme(d.getVar(var)) for var in
               ("TEGRA_SIGNING_PKC", "TEGRA_SIGNING_PKC1", "TEGRA_SIGNING_PKC2")}
    if len(set(schemes.values())) != 1:
        bb.fatal("All three PKC keys must use the same authentication scheme, but "
                 "got: %s" % ", ".join("%s=%s" % (v, s) for v, s in schemes.items()))
    scheme = next(iter(schemes.values()))

    base_bsi = int(d.getVar("TEGRA_FUSE_BOOTSECURITYINFO") or "0x0", 16)
    # Clear bits [3:0], set the scheme in [2:0], and set bit 3 iff an SBK is given.
    resolved = (base_bsi & ~0b1111) | SCHEME_BITS[scheme]
    if d.getVar("TEGRA_SIGNING_SBK"):
        resolved |= 0b1000
    d.setVar("TEGRA_BOOTSECURITYINFO_RESOLVED", "0x%x" % resolved)
    d.setVar("TEGRA_SECUREBOOT_KEY_SCHEME", scheme)
}

# ---------------------------------------------------------------------------
# Build the bundle: compute PKC hashes, render fuse.xml, generate the fuse
# blob (test mode), and assemble the tarball with the tegra-burnfuses wrapper.
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

    # Resolve BootSecurityInfo first. This is SoC-specific and only implemented
    # for t234; for other SoCs the default (non-override) function fatals with a
    # not-implemented message before any work is done.
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

    pkh  = pubkeyhash(d.getVar("TEGRA_SIGNING_PKC"))
    pkh1 = pubkeyhash(d.getVar("TEGRA_SIGNING_PKC1"))
    pkh2 = pubkeyhash(d.getVar("TEGRA_SIGNING_PKC2"))

    # ---- Render fuse.xml -------------------------------------------------
    fuses = [
        ('PscOdmStatic',   4,  d.getVar("TEGRA_FUSE_PSCODMSTATIC")),
        ('PublicKeyHash',  64, pkh),
        ('PkcPubkeyHash1', 64, pkh1),
        ('PkcPubkeyHash2', 64, pkh2),
        ('OptInEnable',    4,  d.getVar("TEGRA_FUSE_OPTINENABLE")),
        ('BootSecurityInfo', 4, boot_security_info),
        ('SecurityMode',   4,  d.getVar("TEGRA_FUSE_SECURITYMODE")),
    ]

    # Read a symmetric-key file (256-bit key as eight big-endian 32-bit words,
    # per the Key Preparation guide) into a fuse value. Returns None when the
    # file is unset/absent/empty or all-zero (i.e. "no key").
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

    # The OEM/disk-encryption key (TEGRA_EKB_FUSE_KEY) -- the same key baked into
    # eks.img by tegra-eks-image -- goes in the OEM key fuse (OemK1 on t234,
    # PscOemKdk1 on t264).
    oem_val = read_fuse_key("TEGRA_EKB_FUSE_KEY")
    if oem_val:
        fuses.insert(1, (d.getVar("TEGRA_FUSE_OEMKEY_NAME"), 32, oem_val))

    # The Secure Boot Key (TEGRA_SIGNING_SBK), when provided, goes in the
    # SecureBootKey fuse and enables boot-image encryption (BootSecurityInfo
    # bit 3, set in the resolver above).
    sbk_val = read_fuse_key("TEGRA_SIGNING_SBK")
    if sbk_val:
        fuses.insert(1, ("SecureBootKey", 32, sbk_val))

    lines = ['<genericfuse MagicId="0x45535546" version="1.0.0">']
    for name, size, value in fuses:
        lines.append('   <fuse name="%s" size="%d" value="%s"/>' % (name, size, value))
    lines.append('</genericfuse>')
    fuse_xml = "\n".join(lines) + "\n"

    # ---- Assemble the self-contained bundle ------------------------------
    # fskp_fuseburn must run inside a full Linux_for_Tegra tree (bootloader
    # configs, firmware, device tools). Copy that tree, then merge the FSKP host
    # overlay (fuseburn + firmware) on top of it.
    bundle = os.path.join(b, "bundle")
    if os.path.exists(bundle):
        shutil.rmtree(bundle)
    os.makedirs(bundle)
    l4t_dst = os.path.join(bundle, "Linux_for_Tegra")

    l4t_src = d.getVar("L4T_SRC_DIR")
    if not os.path.isdir(l4t_src):
        bb.fatal("Full Linux_for_Tegra tree not found at %s (it should be unpacked "
                 "by the tegra-binaries recipe)." % l4t_src)
    bb.note("Copying Linux_for_Tegra from %s (this is large)..." % l4t_src)
    shutil.copytree(l4t_src, l4t_dst, symlinks=True)
    # Merge the FSKP host overlay onto the tree.
    shutil.copytree(d.getVar("FSKP_STAGED_DIR"), l4t_dst, symlinks=True, dirs_exist_ok=True)

    # Locate the fuseburn directory within the assembled tree.
    fuseburn_py = None
    for root, _dirs, files in os.walk(l4t_dst):
        if "fskp_fuseburn.py" in files:
            fuseburn_py = os.path.join(root, "fskp_fuseburn.py")
            break
    if not fuseburn_py:
        bb.fatal("fskp_fuseburn.py not found in the assembled tree; is fskp-tools "
                 "installed correctly?")
    fuseburn_dir = os.path.dirname(fuseburn_py)

    # The board config (-B) is mandatory for t234/t264 and lives at the tree root.
    board_conf = os.path.basename(d.getVar("TEGRA_FUSEBURN_BOARD_CONF") or "")
    conf_path = os.path.join(l4t_dst, board_conf)
    if not board_conf or not os.path.exists(conf_path):
        avail = sorted(f for f in os.listdir(l4t_dst) if f.endswith(".conf"))
        bb.fatal("Board config '%s' not found at the Linux_for_Tegra root. Set "
                 "TEGRA_FUSEBURN_BOARD_CONF to one of:\n  %s"
                 % (board_conf, "\n  ".join(avail)))

    # Write fuse.xml into the fuseburn directory.
    with open(os.path.join(fuseburn_dir, "fuse.xml"), "w") as f:
        f.write(fuse_xml)

    # ---- Generate the --board-spec file from the machine's flash vars ----
    # The board identity comes from the shared tegra-boardvars mapping, so it
    # always matches what this MACHINE flashes. fskp fuseburn wants a slightly
    # different field set than boardvars.sh (RAMCODE_ID instead of RAMCODE,
    # plus FUSELEVEL, and no BOARDREV/CHIPREV).
    bv = dict(tegra_boardvars(d))
    if not bv["BOARDID"]:
        bb.fatal("TEGRA_BOARDID is empty; cannot generate the fuseburn board spec.")
    spec_fields = [
        ("BOARDID",    bv["BOARDID"]),
        ("BOARDSKU",   bv["BOARDSKU"]),
        ("FAB",        bv["FAB"]),
        ("CHIP_SKU",   bv["CHIP_SKU"]),
        ("FUSELEVEL",  d.getVar("TEGRA_FUSEBURN_FUSELEVEL") or "fuselevel_production"),
        ("RAMCODE_ID", bv["RAMCODE"] or "0"),
    ]
    spec_lines = ['%s="%s"' % (k, v) if k == "CHIP_SKU" else '%s=%s' % (k, v)
                  for k, v in spec_fields]
    board_spec = "tegra-secure-boot-board-spec.txt"
    with open(os.path.join(fuseburn_dir, board_spec), "w") as f:
        f.write("\n".join(spec_lines) + "\n")
    bb.note("Generated fuseburn board spec:\n%s" % "\n".join(spec_lines))

    chipid = d.getVar("TEGRA_FUSEBURN_CHIPID") or ""
    conf_rel = os.path.relpath(conf_path, fuseburn_dir)

    # ---- "Fuse the Board" step 1: pre-generate the fuse blob (test mode) --
    cmd = ["python3", "./fskp_fuseburn.py",
           "--board-spec", board_spec,
           "-f", "fuse.xml",
           "--skipfskpkey", "--test",
           "-g", "out",
           "-c", chipid,
           "-B", conf_rel]
    bb.note("Pre-generating fuse blob: %s" % " ".join(cmd))
    try:
        subprocess.check_call(cmd, cwd=fuseburn_dir)
    except subprocess.CalledProcessError as e:
        bb.warn("fskp_fuseburn.py --test could not pre-generate the blob (rc=%d); "
                "tegra-burnfuses will regenerate it at burn time. See the task log "
                "for the fskp_fuseburn output." % e.returncode)

    # ---- "Fuse the Board" step 2: the tegra-burnfuses wrapper ------------
    fuseburn_rel = os.path.relpath(fuseburn_dir, bundle)
    conf_bundle_rel = os.path.relpath(conf_path, bundle)
    script = (
        "#!/bin/sh\n"
        "# Burn (or dry-run) the Jetson secure boot fuses on a device attached\n"
        "# over USB in recovery mode. Generated by tegra-secure-boot-setup.\n"
        "#\n"
        "#   ./tegra-burnfuses            dry-run (fskp_fuseburn --test)\n"
        "#   ./tegra-burnfuses --burn     WARNING: irreversibly burns the fuses\n"
        "set -e\n"
        'here="$(cd "$(dirname "$0")" && pwd)"\n'
        'MODE="--test"\n'
        'case "$1" in\n'
        '  --burn) MODE="--burn" ;;\n'
        '  --test|"") MODE="--test" ;;\n'
        '  *) echo "usage: $0 [--burn|--test]" >&2; exit 1 ;;\n'
        'esac\n'
        'BOARD_CONF="$here/%s"\n'
        'CHIPID="%s"\n'
        'if [ "$MODE" = "--burn" ]; then\n'
        '  echo "*** About to IRREVERSIBLY burn fuses. Press Ctrl-C to abort. ***"\n'
        'fi\n'
        'cd "$here/%s"\n'
        'set -- ./fskp_fuseburn.py --board-spec %s -f fuse.xml \\\n'
        '    --skipfskpkey "$MODE" -g out -c "$CHIPID" -B "$BOARD_CONF"\n'
        'echo "Running: sudo $*"\n'
        'exec sudo "$@"\n'
    ) % (conf_bundle_rel, chipid, fuseburn_rel, board_spec)

    script_path = os.path.join(bundle, "tegra-burnfuses")
    with open(script_path, "w") as f:
        f.write(script)
    os.chmod(script_path, 0o755)

    readme = (
        "Jetson secure boot fuse bundle\n"
        "==============================\n\n"
        "Contents:\n"
        "  Linux_for_Tegra/  - full NVIDIA BSP + FSKP tools; the generated\n"
        "                      fuse.xml, board-spec and pre-built blob (out/) live\n"
        "                      under l4t/tools/flashtools/fuseburn/\n"
        "  tegra-burnfuses   - wrapper to burn/dry-run the fuses\n\n"
        "Usage:\n"
        "  1. Put the target board into Force Recovery mode and attach it over USB.\n"
        "  2. Dry-run first:   ./tegra-burnfuses\n"
        "  3. Burn (final!):   ./tegra-burnfuses --burn\n\n"
        "WARNING: burning fuses is irreversible and permanently modifies the board.\n"
    )
    with open(os.path.join(bundle, "README.txt"), "w") as f:
        f.write(readme)

    # ---- Pack the tarball (large: contains the full Linux_for_Tegra) -----
    tarball = os.path.join(b, d.getVar("TEGRA_SECUREBOOT_TARBALL"))
    with tarfile.open(tarball, "w:gz") as tar:
        tar.add(bundle, arcname="tegra-secure-boot-setup")
}
do_compile[file-checksums] = "${@tegra_signing_filechecksums(d)}"
# BootSecurityInfo depends on the SBK's presence and the PKC key algorithms;
# make sure those inputs are part of this task's signature.
do_compile[vardeps] += "TEGRA_SIGNING_PKC TEGRA_SIGNING_PKC1 TEGRA_SIGNING_PKC2 TEGRA_SIGNING_SBK TEGRA_FUSE_BOOTSECURITYINFO"

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
