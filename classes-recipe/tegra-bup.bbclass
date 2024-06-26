inherit kernel-artifact-names

def bupfile_basename(d):
    if bb.utils.to_boolean(d.getVar('INITRAMFS_IMAGE_BUNDLE')):
        return "${KERNEL_IMAGETYPE}-${INITRAMFS_LINK_NAME}"
    return "${INITRAMFS_IMAGE}-${MACHINE}"

def bup_dependency(d):
    if bb.utils.to_boolean(d.getVar('INITRAMFS_IMAGE_BUNDLE')):
        return ""
    return "${INITRAMFS_IMAGE}:do_image_complete"

BUPFILENAME = "${@bupfile_basename(d)}"
