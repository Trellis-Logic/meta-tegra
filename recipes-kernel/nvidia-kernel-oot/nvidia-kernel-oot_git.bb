SRC_REPO = "github.com/OE4T/nvidia-kernel-oot;protocol=https"
SRC_URI = "gitsm://${SRC_REPO};branch=${SRCBRANCH}"
SRCBRANCH = "main"
SRCREV = "b313e6f5646acffc6f044b98d3bd12bd1d84394b"
PV = "36.4.3+git"

SRC_URI += " \
    file://0001-nvidia-drm-Set-FOP_UNSIGNED_OFFSET-for-nv_drm_fops.f.patch;patchdir=nvdisplay \
    file://0001-gpu-drm-tegra-Fix-support-for-Linux-v6.12.patch;patchdir=nvidia-oot \
    file://0001-gpu-host1x-Memory-context-stealing.patch;patchdir=nvidia-oot \
    file://0002-gpu-host1x-When-out-of-memory-contexts-wait-for-free.patch;patchdir=nvidia-oot \
    file://0003-gpu-host1x-Set-up-device-DMA-parameters.patch;patchdir=nvidia-oot \
"

COMPATIBLE_MACHINE = "(tegra)"

S = "${WORKDIR}/git"

require nvidia-kernel-oot.inc
