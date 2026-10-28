# Crop a region of the screen and scale it up, for reading the minimap.
param(
  [int]$X = 1330, [int]$Y = 20, [int]$W = 200, [int]$H = 180,
  [int]$Scale = 4, [string]$Out = "D:\Game\rwpp-probe\tools\crop.png"
)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class DPI2 { [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v); }
"@
[void][DPI2]::SetProcessDpiAwarenessContext([IntPtr](-4))
$bmp = New-Object System.Drawing.Bitmap 1536, 864
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen(0, 0, 0, 0, (New-Object System.Drawing.Size 1536, 864))
$rect = New-Object System.Drawing.Rectangle $X, $Y, $W, $H
$crop = $bmp.Clone($rect, $bmp.PixelFormat)
$big = New-Object System.Drawing.Bitmap ($W * $Scale), ($H * $Scale)
$g2 = [System.Drawing.Graphics]::FromImage($big)
$g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g2.DrawImage($crop, 0, 0, ($W * $Scale), ($H * $Scale))
$big.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $g2.Dispose(); $bmp.Dispose(); $crop.Dispose(); $big.Dispose()
Write-Output "saved $Out"
