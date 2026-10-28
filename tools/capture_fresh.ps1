param(
  [string]$Out = "D:/Game/rwpp-probe/tools/shots/fresh.png",
  [int]$WaitMs = 500
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$sig = @"
using System;
using System.Runtime.InteropServices;
public class FR {
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@
Add-Type -TypeDefinition $sig
[void][FR]::SetProcessDpiAwarenessContext([IntPtr](-4))

$p = Get-Process RWPP -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $p) { Write-Host "NO_WINDOW"; exit 1 }
$h = $p.MainWindowHandle

[void][FR]::ShowWindow($h, 6)
Start-Sleep -Milliseconds 250
[void][FR]::ShowWindow($h, 9)
[void][FR]::SetForegroundWindow($h)
Start-Sleep -Milliseconds $WaitMs

$r = New-Object FR+RECT
[void][FR]::GetClientRect($h, [ref]$r)
$w = $r.Right - $r.Left; $ht = $r.Bottom - $r.Top
$bmp = New-Object System.Drawing.Bitmap($w, $ht)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$hdc = $g.GetHdc()
$ok = [FR]::PrintWindow($h, $hdc, 2)
$g.ReleaseHdc($hdc); $g.Dispose()
$dir = Split-Path -Parent $Out
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Host ("pw={0} saved={1} {2}x{3} md5={4}" -f $ok, $Out, $w, $ht, (Get-FileHash $Out -Algorithm MD5).Hash)
