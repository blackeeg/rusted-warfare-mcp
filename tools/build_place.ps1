param(
  [int]$BX = 1379,
  [int]$BY = 374,
  [int]$PX = 1109,
  [int]$PY = 700,
  [int]$Hwnd = 0,
  [switch]$NoPlace
)
$ErrorActionPreference = "Stop"

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class BP {
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte s, uint f, UIntPtr e);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
}
"@

$p = Get-Process RWPP -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $p) { Write-Host "NO_WINDOW"; exit 1 }
$h = $p.MainWindowHandle

[void][BP]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 80
[void][BP]::ShowWindow($h, 9)
[void][BP]::SetForegroundWindow($h)
Start-Sleep -Milliseconds 180
[void][BP]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 320
$fg = [BP]::GetForegroundWindow()
Write-Host ("foreground=" + $fg + " game=" + $h + " match=" + ($fg -eq $h))

function Invoke-Click([int]$x, [int]$y) {
  [void][BP]::SetCursorPos(400, 830)
  Start-Sleep -Milliseconds 150
  [void][BP]::SetCursorPos($x, $y)
  Start-Sleep -Milliseconds 300
  [BP]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
  Start-Sleep -Milliseconds 110
  [BP]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
  Start-Sleep -Milliseconds 400
}

Write-Host ("panel click " + $BX + "," + $BY)
Invoke-Click $BX $BY
if (-not $NoPlace) {
  Write-Host ("place click " + $PX + "," + $PY)
  Invoke-Click $PX $PY
}
Write-Host "done"
