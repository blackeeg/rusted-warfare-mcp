# Minimize every visible top-level window except the game, focus the game, click, screenshot.
param([int]$X = 84, [int]$Y = 484, [string]$Out = "D:\Game\rwpp-probe\tools\t-min.png", [switch]$Restore)
Add-Type @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public class MW {
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc p, IntPtr l);
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetWindowTextLength(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern int GetWindowThreadProcessId(IntPtr h, out int pid);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);

  public static List<IntPtr> Collect() {
    List<IntPtr> list = new List<IntPtr>();
    EnumWindows(delegate(IntPtr h, IntPtr l) {
      if (IsWindowVisible(h) && GetWindowTextLength(h) > 0) list.Add(h);
      return true;
    }, IntPtr.Zero);
    return list;
  }
}
"@
[void][MW]::SetProcessDpiAwarenessContext([IntPtr](-4))
$game = Get-Process RWPP -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $game) { Write-Output "NO_WINDOW"; exit 1 }
$gh = $game.MainWindowHandle

$minimized = @()
foreach ($h in [MW]::Collect()) {
    if ($h -eq $gh) { continue }
    $pid2 = 0
    [void][MW]::GetWindowThreadProcessId($h, [ref]$pid2)
    if ($pid2 -eq $game.Id) { continue }
    [void][MW]::ShowWindow($h, 6)   # SW_MINIMIZE
    $minimized += $h
}
Write-Output ("minimized=" + $minimized.Count)

[void][MW]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 70
[void][MW]::ShowWindow($gh, 9)
[void][MW]::SetForegroundWindow($gh)
Start-Sleep -Milliseconds 120
[void][MW]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero); Start-Sleep -Milliseconds 300

[void][MW]::SetCursorPos($X, $Y)
Start-Sleep -Milliseconds 200
[MW]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 100
[MW]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 500

Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap 1536, 864
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen(0, 0, 0, 0, (New-Object System.Drawing.Size 1536, 864))
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

Write-Output ("fg=" + [MW]::GetForegroundWindow() + " game=" + $gh + " saved=" + $Out)
if ($Restore) { foreach ($h in $minimized) { [void][MW]::ShowWindow($h, 9) } }
