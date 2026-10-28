# Force the game window to the foreground (ALT trick), verify, then click.
param([int]$X = 700, [int]$Y = 500, [switch]$Right)
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class FC {
  [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public MOUSEINPUT mi; }
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] inputs, int size);
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
}
"@
[void][FC]::SetProcessDpiAwarenessContext([IntPtr](-4))
$p = Get-Process RWPP -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $p) { Write-Output "no window"; exit 1 }
$h = $p.MainWindowHandle

function Force-Foreground($hwnd) {
    [void][FC]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)      # ALT down
    Start-Sleep -Milliseconds 80
    [void][FC]::ShowWindow($hwnd, 9)                           # SW_RESTORE
    [void][FC]::SetForegroundWindow($hwnd)
    Start-Sleep -Milliseconds 120
    [void][FC]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)       # ALT up
}

Force-Foreground $h
Start-Sleep -Milliseconds 500
$fg = [FC]::GetForegroundWindow()
if ($fg -ne $h) {
    # 再试一次
    Force-Foreground $h
    Start-Sleep -Milliseconds 400
    $fg = [FC]::GetForegroundWindow()
}
Write-Output "foreground=$fg target=$h match=$($fg -eq $h)"
if ($fg -ne $h) { Write-Output "FAILED_TO_FOCUS"; exit 2 }

[void][FC]::SetCursorPos($X, $Y)
Start-Sleep -Milliseconds 250
$down = if ($Right) { 0x0008 } else { 0x0002 }
$up = if ($Right) { 0x0010 } else { 0x0004 }
$i = New-Object FC+INPUT; $i.type = 0; $i.mi.dwFlags = $down
[void][FC]::SendInput(1, @($i), [System.Runtime.InteropServices.Marshal]::SizeOf($i))
Start-Sleep -Milliseconds 140
$j = New-Object FC+INPUT; $j.type = 0; $j.mi.dwFlags = $up
[void][FC]::SendInput(1, @($j), [System.Runtime.InteropServices.Marshal]::SizeOf($j))
Start-Sleep -Milliseconds 200
Write-Output ("clicked at " + $X + "," + $Y + " fg_after=" + [FC]::GetForegroundWindow())
