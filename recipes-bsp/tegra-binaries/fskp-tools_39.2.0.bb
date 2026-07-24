DESCRIPTION = "NVIDIA Factory Secure Key Provisioning (FSKP) tools, including \
the fskp_fuseburn utility used to burn ODM fuses on Jetson devices over USB."
LICENSE = "LicenseRef-Proprietary"
LIC_FILES_CHKSUM = "file://Tegra_Software_License_Agreement-Tegra-Linux.txt;md5=376d20bd5275442226fcdf54e4844ddf"

inherit l4t_bsp

# The FSKP host tools are published as a "host overlay" archive alongside the
# rest of the L4T BSP.  l4t_bsp gives us L4T_URI_BASE (.../<release>/release)
# and L4T_VERSION for the currently selected branch, so the download URL tracks
# the machine/branch configuration automatically.  For R39.2.0 this resolves to:
#   https://developer.nvidia.com/downloads/embedded/L4T/r39_Release_v2.0/release/host_overlay_fskp_tools_R39.2.0_aarch64.tbz2
SRC_URI = "${L4T_URI_BASE}/host_overlay_fskp_tools_R${L4T_VERSION}_aarch64.tbz2"

# NOTE: This checksum must match the archive for the configured L4T_VERSION.
# Update it (bitbake will print the expected value on the first fetch) whenever
# the branch/version changes.
SRC_URI[sha256sum] = "b5aa3782ab33940c14b9c3238a42a41512d33dc9992786e531e59a74e15ce06d"

# The overlay archive unpacks to a Linux_for_Tegra/ root (holding the license
# file and l4t/tools/flashtools/fuseburn/), same as the main BSP archive.
S = "${UNPACKDIR}/Linux_for_Tegra"

# These are host-side tools (like tegra-flashtools), so gate on the host rather
# than the target machine. Gating on COMPATIBLE_MACHINE would skip the -native
# variant, which has no machine context.
COMPATIBLE_HOST = "(x86_64.*|aarch64.*)"
INHIBIT_DEFAULT_DEPS = "1"

do_configure[noexec] = "1"
do_compile[noexec] = "1"

# Directory under datadir where the unpacked FSKP tool tree is staged.  The
# archive is an overlay for Linux_for_Tegra, so we preserve its layout verbatim
# and let consumers locate fskp_fuseburn.py within it.
FSKP_INSTALL_DIR = "${datadir}/tegra-fskp"

do_install() {
    install -d ${D}${FSKP_INSTALL_DIR}
    cp -R --no-dereference --preserve=links,mode,timestamps ${S}/. ${D}${FSKP_INSTALL_DIR}/
    # Sanity check: the fuseburn utility must be present for consumers to use.
    if [ -z "$(find ${D}${FSKP_INSTALL_DIR} -name fskp_fuseburn.py -print -quit)" ]; then
        bbfatal "fskp_fuseburn.py not found in the FSKP tools archive; the archive layout may have changed."
    fi
}

FILES:${PN} = "${datadir}"

INHIBIT_PACKAGE_STRIP = "1"
INHIBIT_PACKAGE_DEBUG_SPLIT = "1"
INHIBIT_SYSROOT_STRIP = "1"
# Prebuilt NVIDIA host binaries; skip QA that does not apply to them.
INSANE_SKIP:${PN} = "arch already-stripped"

BBCLASSEXTEND = "native nativesdk"
