param(
  [string]$Out = "D:/Game/rwpp-probe/tools/shots/cap.png",
  [string]$TitleLike = "",
  [switch]$List
)
$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing

$sig = @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class WCap {
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc lpEnumFunc, IntPtr lParam);
  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr hWnd, StringBuilder s, int n);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassNameW(IntPtr hWnd, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hWnd, out RECT r);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  public static string Txt(IntPtr h) { var sb = new StringBuilder(512); GetWindowTextW(h, sb, 512); return sb.ToString(); }
  public static string Cls(IntPtr h) { var sb = new StringBuilder(512); GetClassNameW(h, sb, 512); return sb.ToString(); }
}
"@
Add-Type -TypeDefinition $sig

$targets = New-Object System.Collections.ArrayList
$cb = [WCap+EnumProc]{
  param($h, $l)
  if (-not [WCap]::IsWindowVisible($h)) { return $true }
  $wpid = 0
  [void][WCap]::GetWindowThreadProcessId($h, [ref]$wpid)
  try { $pn = (Get-Process -Id $wpid -ErrorAction Stop).ProcessName } catch { return $true }
  if ($pn -ne "RWPP") { return $true }
  $t = [WCap]::Txt($h); $c = [WCap]::Cls($h)
  $wr = New-Object WCap+RECT
  [void][WCap]::GetWindowRect($h, [ref]$wr)
  $cr = New-Object WCap+RECT
  [void][WCap]::GetClientRect($h, [ref]$cr)
  $w = $wr.Right - $wr.Left; $hgt = $wr.Bottom - $wr.Top
  $cw = $cr.Right - $cr.Left; $ch = $cr.Bottom - $cr.Top
  [void]$targets.Add([pscustomobject]@{ Hwnd=$h; Pid=$wpid; Title=$t; Class=$c; X=$wr.Left; Y=$wr.Top; W=$w; H=$hgt; CW=$cw; CH=$ch })
  return $true
}
[void][WCap]::EnumWindows($cb, [IntPtr]::Zero)

if ($List) {
  $targets | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
  exit 0
}

if ($targets.Count -eq 0) { Write-Host "NO_RWPP_WINDOW"; exit 1 }

$t = $targets | Sort-Object -Property @{Expression={$_.W*$_.H}; Descending=$true} | Select-Object -First 1
if ($TitleLike -ne "") {
  $f = $targets | Where-Object { $_.Title -like "*$TitleLike*" } | Select-Object -First 1
  if ($f) { $t = $f }
}

$fg = [WCap]::GetForegroundWindow()
Write-Host ("Hwnd={0} Pid={1} Title='{2}' Class={3} Rect=({4},{5})-({6}x{7}) Client={8}x{9} Fg={10} FgMatch={11}" -f `
  $t.Hwnd, $t.Pid, $t.Title, $t.Class, $t.X, $t.Y, $t.W, $t.H, $t.CW, $t.CH, $fg, ($fg -eq $t.Hwnd))

$bmp = New-Object System.Drawing.Bitmap($t.CW, $t.CH)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$hdc = $g.GetHdc()
$ok = [WCap]::PrintWindow($t.Hwnd, $hdc, 2)
$g.ReleaseHdc($hdc)
$g.Dispose()
if (-not $ok) { Write-Host "PRINTWINDOW_FAIL" }
$dir = Split-Path -Parent $Out
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
$md5 = (Get-FileHash $Out -Algorithm MD5).Hash
Write-Host ("Saved {0} {1}x{2} md5={3}" -f $Out, $t.CW, $t.CH, $md5)
