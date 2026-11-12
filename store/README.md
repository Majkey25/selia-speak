# Store artwork

`icon-512.png` is generated from the native S-wave geometry.
Regenerate on Windows from the repository root:

```powershell
pwsh -NoProfile -File scripts/export-store-icon.ps1 -Destination "$PWD/store/icon-512.png"
```

The script checks the required 512 by 512 pixel dimensions.
