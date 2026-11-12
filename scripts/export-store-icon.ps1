param([Parameter(Mandatory)][string]$Destination)
# Reproduce store/icon-512.png from the native S-wave icon geometry on Windows.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$bitmap = [System.Drawing.Bitmap]::new(512, 512)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$path = [System.Drawing.Drawing2D.GraphicsPath]::new()
$pen = [System.Drawing.Pen]::new([System.Drawing.Color]::White, 7)
try {
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#245BDB'))
    $graphics.ScaleTransform(512 / 108, 512 / 108)
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $path.AddLine(68, 34, 48, 34)
    $path.AddBezier(48, 34, 38, 34, 35, 48, 46, 51)
    $path.AddLine(46, 51, 62, 56)
    $path.AddBezier(62, 56, 73, 59, 70, 74, 59, 74)
    $path.AddLine(59, 74, 40, 74)
    $graphics.DrawPath($pen, $path)
    $graphics.DrawLine($pen, 28, 48, 28, 60)
    $graphics.DrawLine($pen, 80, 48, 80, 60)
    [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($Destination)) | Out-Null
    $bitmap.Save($Destination, [System.Drawing.Imaging.ImageFormat]::Png)
    if ($bitmap.Width -ne 512 -or $bitmap.Height -ne 512) { throw 'Store icon must be 512 by 512 pixels.' }
    Write-Output "Exported 512 x 512 PNG: $Destination"
} finally {
    $pen.Dispose()
    $path.Dispose()
    $graphics.Dispose()
    $bitmap.Dispose()
}
