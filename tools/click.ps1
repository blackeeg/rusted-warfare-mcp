# 单次点击（测试用）：把光标放到 x,y，按下再松开
param([int]$X = 100, [int]$Y = 800)
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class CLK {
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
}
"@
[void][CLK]::SetCursorPos($X, $Y)
Start-Sleep -Milliseconds 300
[CLK]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 100
[CLK]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
Write-Output "clicked $X,$Y"
