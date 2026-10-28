# 截取 1536x864 画面（窗口已校准到 0,0 才有意义）
param([string]$Out = "D:\Game\rwpp-probe\tools\shot.png")
if (-not $Out -or $Out.Trim() -eq '') { $Out = "D:\Game\rwpp-probe\tools\shot.png" }
if ($args.Count -ge 1) { $Out = [string]$args[0] }
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class DPI { [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v); }
"@
[void][DPI]::SetProcessDpiAwarenessContext([IntPtr](-4))
$bmp = New-Object System.Drawing.Bitmap 1536, 864
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen(0, 0, 0, 0, (New-Object System.Drawing.Size 1536, 864))
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output "SAVED $Out"
