# Canonical mapping of a Tegra machine's board-identity flash variables to the
# KEY=VALUE form consumed by NVIDIA host tools.
#
# The same board identity is needed in more than one place: image_types_tegra
# emits a boardvars.sh for flashing, and tegra-secure-boot-setup emits a
# --board-spec file for fskp fuseburn. Keeping the variable->field mapping here
# means consumers don't have to re-encode which TEGRA_* variables (note the
# CHIP_SKU/RAMCODE ones live under TEGRA_FLASHVAR_*) describe the board.

# Ordered (key, value) pairs describing the board identity for the current
# MACHINE. Returned as a list so callers can preserve ordering and pick the
# subset/format they need.
def tegra_boardvars(d):
    return [
        ("BOARDID",  d.getVar("TEGRA_BOARDID") or ""),
        ("FAB",      d.getVar("TEGRA_FAB") or ""),
        ("BOARDSKU", d.getVar("TEGRA_BOARDSKU") or ""),
        ("BOARDREV", d.getVar("TEGRA_BOARDREV") or ""),
        ("CHIPREV",  d.getVar("TEGRA_CHIPREV") or ""),
        ("CHIP_SKU", d.getVar("TEGRA_FLASHVAR_CHIP_SKU") or ""),
        ("RAMCODE",  d.getVar("TEGRA_FLASHVAR_RAMCODE") or ""),
    ]

# boardvars.sh-style rendering (KEY="VALUE" per line), matching what
# image_types_tegra writes.
def tegra_render_boardvars(d):
    return "".join('%s="%s"\n' % (k, v) for k, v in tegra_boardvars(d))
