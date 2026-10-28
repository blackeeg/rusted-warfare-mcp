# 窗口几何校准：读 RWPP 主窗口，必要时摆成 1536x864 @ (0,0)
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class GEO {
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out R r);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref P p);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int x, int y, int cx, int cy, uint f);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  public struct R { public int L,T,Rr,B; }
  public struct P { public int X,Y; }
}
"@
[void][GEO]::SetProcessDpiAwarenessContext([IntPtr](-4))

$proc = Get-Process RWPP -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -ne '' } | Select-Object -First 1
if (-not $proc) { Write-Output "NO_WINDOW"; exit 1 }
$h = $proc.MainWindowHandle
if ($h -eq 0) { Write-Output "NO_HANDLE"; exit 2 }

$wr = New-Object GEO+R; [void][GEO]::GetWindowRect($h,[ref]$wr)
$cr = New-Object GEO+R; [void][GEO]::GetClientRect($h,[ref]$cr)
$org = New-Object GEO+P; [void][GEO]::ClientToScreen($h,[ref]$org)

$cw = $cr.Rr - $cr.L
$ch = $cr.B - $cr.T
$ok = ($cw -eq 1536 -and $ch -eq 864 -and $wr.L -eq 0 -and $wr.T -eq 0 -and $org.X -eq 0 -and $org.Y -eq 0)

if (-not $ok) {
  # 先算边框差，保证客户区正好 1536x864
  $dw = ($wr.Rr - $wr.L) - $cw
  $dh = ($wr.B - $wr.T) - $ch
  [void][GEO]::SetWindowPos($h, [IntPtr]::Zero, 0, 0, (1536 + $dw), (864 + $dh), 0x0040)
  Start-Sleep -Milliseconds 600
  [void][GEO]::GetWindowRect($h,[ref]$wr)
  [void][GEO]::GetClientRect($h,[ref]$cr)
  [void][GEO]::ClientToScreen($h,[ref]$org)
  $cw = $cr.Rr - $cr.L
  $ch = $cr.B - $cr.T
  $ok = ($cw -eq 1536 -and $ch -eq 864 -and $wr.L -eq 0 -and $wr.T -eq 0 -and $org.X -eq 0 -and $org.Y -eq 0)
}
[void][GEO]::SetForegroundWindow($h)
Write-Output ("client=" + $cw + "x" + $ch + " window=" + $wr.L + "," + $wr.T + " org=" + $org.X + "," + $org.Y + " title=" + $proc.MainWindowTitle)
if ($ok) { Write-Output "CALIBRATED" } else { Write-Output "NOT_CALIBRATED" }
