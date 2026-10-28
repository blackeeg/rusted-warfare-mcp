# Report game window handle vs foreground window handle.
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class FG {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowThreadProcessId(IntPtr h, out int pid);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr SetActiveWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
}
"@
$p = Get-Process RWPP -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -ne '' } | Select-Object -First 1
if (-not $p) { Write-Output "no rwpp window"; exit 1 }
$h = $p.MainWindowHandle
$fg = [FG]::GetForegroundWindow()
$fgPid = 0
[void][FG]::GetWindowThreadProcessId($fg, [ref]$fgPid)
Write-Output "rwppHandle=$h  rwppPid=$($p.Id)"
Write-Output "foreground=$fg  fgPid=$fgPid"
Write-Output "same=$($fg -eq $h)"
