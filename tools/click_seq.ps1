param(
  [Parameter(Mandatory=$true)][string]$Clicks,
  [int]$GapMs = 150,
  [switch]$NoFocus
)
$ErrorActionPreference = "Stop"

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class CS {
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

if (-not $NoFocus) {
  [void][CS]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)
  Start-Sleep -Milliseconds 80
  [void][CS]::ShowWindow($h, 9)
  [void][CS]::SetForegroundWindow($h)
  Start-Sleep -Milliseconds 180
  [void][CS]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
  Start-Sleep -Milliseconds 320
}
$fg = [CS]::GetForegroundWindow()
Write-Host ("foreground=" + $fg + " game=" + $h + " match=" + ($fg -eq $h))

foreach ($pair in $Clicks.Split(';')) {
  $xy = $pair.Split(',')
  $x = [int]$xy[0]; $y = [int]$xy[1]
  $right = $false
  $ctrl = $false
  if ($xy.Length -gt 2) {
    if ($xy[2] -eq 'r') { $right = $true }
    if ($xy[2] -eq 'c') { $ctrl = $true }
  }
  [void][CS]::SetCursorPos(400, 830)
  Start-Sleep -Milliseconds 80
  [void][CS]::SetCursorPos($x, $y)
  Start-Sleep -Milliseconds 70
  if ($ctrl) { [CS]::keybd_event(0x11, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60 }
  $down = if ($right) { 0x0008 } else { 0x0002 }
  $up = if ($right) { 0x0010 } else { 0x0004 }
  [CS]::mouse_event($down, 0, 0, 0, [UIntPtr]::Zero)
  Start-Sleep -Milliseconds 70
  [CS]::mouse_event($up, 0, 0, 0, [UIntPtr]::Zero)
  Start-Sleep -Milliseconds 60
  if ($ctrl) { [CS]::keybd_event(0x11, 0, 2, [UIntPtr]::Zero) }
  $tag = ""
  if ($right) { $tag = " right" }
  if ($ctrl) { $tag = $tag + " ctrl" }
  Write-Host ("click " + $x + "," + $y + $tag)
  Start-Sleep -Milliseconds $GapMs
}
Write-Host "done"
