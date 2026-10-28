# 往当前前台窗口发按键（测试用）：keys.ps1 -Keys "z,x,b" -Repeat 3
param([string]$Keys = "z,x,b", [int]$Repeat = 3)
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class KB {
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
"@
$map = @{
  'a'=0x41; 'b'=0x42; 'c'=0x43; 'd'=0x44; 'e'=0x45; 'f'=0x46; 'g'=0x47; 'h'=0x48;
  'i'=0x49; 'j'=0x4A; 'k'=0x4B; 'l'=0x4C; 'm'=0x4D; 'n'=0x4E; 'o'=0x4F; 'p'=0x50;
  'q'=0x51; 'r'=0x52; 's'=0x53; 't'=0x54; 'u'=0x55; 'v'=0x56; 'w'=0x57; 'x'=0x58;
  'y'=0x59; 'z'=0x5A; 'esc'=0x1B; 'space'=0x20
}
foreach ($k in $Keys.Split(',')) {
  $name = $k.Trim().ToLower()
  if (-not $map.ContainsKey($name)) { Write-Output "skip unknown key: $name"; continue }
  $vk = [byte]$map[$name]
  for ($i = 0; $i -lt $Repeat; $i++) {
    [KB]::keybd_event($vk, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 60
    [KB]::keybd_event($vk, 0, 2, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 160
  }
  Write-Output "sent $name x$Repeat"
}
