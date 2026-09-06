import { readFile, writeFile, mkdir } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";

// No fonts, timestamps, metadata, or random inputs enter the generated assets.
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const source = await readFile(resolve(root, "app/webApp/src/webMain/resources/treasury.svg"));
const desktop = resolve(root, "app/desktopApp/icons");
const ios = resolve(root, "app/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png");
await mkdir(desktop, { recursive: true });

async function render(size, opaque = false) {
    let image = sharp(source, { density: 768 }).resize(size, size);
    if (opaque) image = image.flatten({ background: "#174A3C" }).removeAlpha();
    return image.png({ compressionLevel: 9, adaptiveFiltering: false, palette: false }).toBuffer();
}

const iosPng = await render(1024, true);
const metadata = await sharp(iosPng).metadata();
if (metadata.width !== 1024 || metadata.height !== 1024 || metadata.hasAlpha)
    throw new Error("iOS icon must be an opaque 1024×1024 RGB PNG");
await writeFile(ios, iosPng);
await writeFile(resolve(desktop, "treasury.png"), await render(1024));

// ICO permits PNG-compressed frames. Windows selects the closest actual size.
const sizes = [16, 24, 32, 48, 64, 128, 256];
const images = await Promise.all(sizes.map(size => render(size)));
const directory = Buffer.alloc(6 + images.length * 16);
directory.writeUInt16LE(1, 2);
directory.writeUInt16LE(images.length, 4);
let offset = directory.length;
for (let index = 0; index < images.length; index++) {
    const position = 6 + index * 16;
    directory[position] = sizes[index] === 256 ? 0 : sizes[index];
    directory[position + 1] = directory[position];
    directory.writeUInt16LE(1, position + 4);
    directory.writeUInt16LE(32, position + 6);
    directory.writeUInt32LE(images[index].length, position + 8);
    directory.writeUInt32LE(offset, position + 12);
    offset += images[index].length;
}
await writeFile(resolve(desktop, "treasury.ico"), Buffer.concat([directory, ...images]));

// ICNS containers use big-endian lengths and modern PNG-backed icon elements.
const types = [["icp4", 16], ["icp5", 32], ["icp6", 64], ["ic07", 128],
    ["ic08", 256], ["ic09", 512], ["ic10", 1024], ["ic11", 32], ["ic12", 64],
    ["ic13", 256], ["ic14", 512]];
const chunks = await Promise.all(types.map(async ([type, size]) => {
    const png = await render(size);
    const header = Buffer.alloc(8);
    header.write(type, 0, 4, "ascii");
    header.writeUInt32BE(png.length + 8, 4);
    return Buffer.concat([header, png]);
}));
const header = Buffer.alloc(8);
header.write("icns", 0, 4, "ascii");
header.writeUInt32BE(8 + chunks.reduce((sum, chunk) => sum + chunk.length, 0), 4);
await writeFile(resolve(desktop, "treasury.icns"), Buffer.concat([header, ...chunks]));

console.log("Generated opaque iOS PNG and desktop PNG, ICO, and ICNS from treasury.svg");
