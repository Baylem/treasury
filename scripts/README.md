# Icon generation

The maintained, font-free source is
`app/webApp/src/webMain/resources/treasury.svg`. It has an accessible Treasury
title and uses the same geometry and colors as the Android vector launcher icon.

With Node.js 22 or newer:

```shell
npm ci --prefix scripts
npm run icons --prefix scripts
```

The lockfile pins [Sharp](https://sharp.pixelplumbing.com/) and its standard
libvips SVG renderer. No image-generation service or external assets are used.
Generated assets are checked in, so ordinary Gradle/Xcode builds do not need Node
or regenerate branding.

Outputs are an opaque, RGB 1024 × 1024 iOS App Store icon, a transparent desktop
PNG, a Windows ICO containing 16/24/32/48/64/128/256-pixel PNG frames, and a macOS
ICNS containing standard and Retina PNG representations. The ICO/ICNS containers
contain only the rendered pixels and deterministic format headers. No build
timestamp or random data is embedded. Re-running with the same locked tools
produces identical files.

After changing the SVG, regenerate and inspect the assets, verify the iOS PNG has
no alpha channel, and install platform packages to inspect launcher rendering.
The Android XML paths are maintained alongside the SVG and must be updated for
geometry changes. Platform launchers provide the accessible name from the app's
Treasury display name.
