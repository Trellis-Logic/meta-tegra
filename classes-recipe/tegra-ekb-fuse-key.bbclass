# Shared logic for deriving TEGRA_EKB_FUSE_KEY.
#
# TEGRA_EKB_FUSE_KEY is the symmetric OEM key that is both:
#   * programmed into the OEM key fuse when setting up secure boot, and
#   * consumed by gen_ekb.py when generating the encrypted key blob (eks.img).
#
# Because the same key value must be used in both places, the logic for
# deriving it is factored out here so that tegra-eks-image (which bakes it
# into eks.img) and tegra-secure-boot-setup (which burns it into the fuse)
# agree on the value.
#
# The fuse that stores the key differs by SoC:
#   * t234 stores it in the OEM_K1 fuse    -> TEGRA_EKB_OEM_K1
#   * t264 stores it in the PscOemKdk1 fuse -> TEGRA_EKB_OEM_KDK1
#
# If the secure boot fuse is not set, these can be left empty or set to
# 0000000000000000000000000000000000000000000000000000000000000000.
#
# See https://docs.nvidia.com/jetson/archives/r36.4.3/DeveloperGuide/SD/Security/OpTee.html#ekb-encrypted-key-blob

TEGRA_EKB_OEM_K1 ?= ""
TEGRA_EKB_OEM_KDK1 ?= ""

TEGRA_EKB_FUSE_KEY = ""
TEGRA_EKB_FUSE_KEY:tegra234 = "${TEGRA_EKB_OEM_K1}"
TEGRA_EKB_FUSE_KEY:tegra264 = "${TEGRA_EKB_OEM_KDK1}"
