# Future Solar boot animation

Drop custom assets here when ready to replace the stock Innioasis boot splash:

| File | Destination |
|------|-------------|
| `boot.img` | ROM archive `boot.img` (kernel ramdisk) |
| `logo.bin` | ROM archive `logo.bin` (MTK early splash) |
| `bootanimation.zip` | `/system/media/bootanimation.zip` |
| `bootanimation` | `/system/bin/bootanimation` |

Until then, `../innioasis-boot/` supplies stock Innioasis 3.0.7 boot media via `apply-innioasis-boot.sh`.
