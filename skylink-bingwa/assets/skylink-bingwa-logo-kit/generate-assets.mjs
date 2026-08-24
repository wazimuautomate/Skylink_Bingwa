import fs from "node:fs/promises";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const sharp = require("sharp");

const projectRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../..",
);

const inputPath = path.join(
  projectRoot,
  "assets",
  "new-logo.png",
);

const outputRoot = path.join(projectRoot, "assets", "skylink-bingwa-logo-kit");
const sourceDir = path.join(outputRoot, "source");
const brandDir = path.join(outputRoot, "brand");
const androidDir = path.join(outputRoot, "android");
const webDir = path.join(outputRoot, "web");
const previewDir = path.join(outputRoot, "preview");

const densitySizes = {
  mdpi: 48,
  hdpi: 72,
  xhdpi: 96,
  xxhdpi: 144,
  xxxhdpi: 192,
};

const notificationSizes = {
  mdpi: 24,
  hdpi: 36,
  xhdpi: 48,
  xxhdpi: 72,
  xxxhdpi: 96,
};

await Promise.all(
  [sourceDir, brandDir, androidDir, webDir, previewDir].map((directory) =>
    fs.mkdir(directory, { recursive: true }),
  ),
);

await fs.copyFile(
  inputPath,
  path.join(sourceDir, "new-logo.png"),
);

const { data: sourcePixels, info: sourceInfo } = await sharp(inputPath)
  .removeAlpha()
  .raw()
  .toBuffer({ resolveWithObject: true });

const extractedPixels = Buffer.alloc(sourceInfo.width * sourceInfo.height * 4);

for (let pixel = 0; pixel < sourceInfo.width * sourceInfo.height; pixel += 1) {
  const sourceOffset = pixel * 3;
  const outputOffset = pixel * 4;
  const red = sourcePixels[sourceOffset];
  const green = sourcePixels[sourceOffset + 1];
  const blue = sourcePixels[sourceOffset + 2];
  const maximum = Math.max(red, green, blue);
  const minimum = Math.min(red, green, blue);
  const chroma = maximum - minimum;

  // The approved logo is a full-colour mark on white. Remove only the white
  // canvas while retaining its black outline, green type, orange accents and
  // soft edge shading for transparent Android assets.
  const distanceFromWhite = 255 - minimum;
  let alpha = Math.max(0, Math.min(1, (distanceFromWhite - 18) / 72));
  if (alpha < 0.025) alpha = 0;

  const recoverFromWhite = (channel) => {
    if (alpha === 0) return 0;
    return Math.max(
      0,
      Math.min(255, Math.round((channel - 255 * (1 - alpha)) / alpha)),
    );
  };

  extractedPixels[outputOffset] = recoverFromWhite(red);
  extractedPixels[outputOffset + 1] = recoverFromWhite(green);
  extractedPixels[outputOffset + 2] = recoverFromWhite(blue);
  extractedPixels[outputOffset + 3] = Math.round(alpha * 255);
}

const extractedFull = await sharp(extractedPixels, {
  raw: {
    width: sourceInfo.width,
    height: sourceInfo.height,
    channels: 4,
  },
})
  .png()
  .toBuffer();

const trimmedSymbol = await sharp(extractedFull)
  .trim({
    background: { r: 0, g: 0, b: 0, alpha: 0 },
    threshold: 2,
  })
  .png()
  .toBuffer();

const symbolMetadata = await sharp(trimmedSymbol).metadata();

async function transparentSymbolCanvas(size, fillRatio = 0.78) {
  const maximumDimension = Math.round(size * fillRatio);
  const symbol = await sharp(trimmedSymbol)
    .resize({
      width: maximumDimension,
      height: maximumDimension,
      fit: "inside",
      withoutEnlargement: false,
    })
    .png()
    .toBuffer();
  const metadata = await sharp(symbol).metadata();

  return sharp({
    create: {
      width: size,
      height: size,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  })
    .composite([
      {
        input: symbol,
        left: Math.round((size - metadata.width) / 2),
        top: Math.round((size - metadata.height) / 2),
      },
    ])
    .png()
    .toBuffer();
}

async function solidSymbolCanvas(
  size,
  fillRatio,
  background,
  symbolBuffer = trimmedSymbol,
) {
  const maximumDimension = Math.round(size * fillRatio);
  const symbol = await sharp(symbolBuffer)
    .resize({
      width: maximumDimension,
      height: maximumDimension,
      fit: "inside",
      withoutEnlargement: false,
    })
    .png()
    .toBuffer();
  const metadata = await sharp(symbol).metadata();

  return sharp({
    create: {
      width: size,
      height: size,
      channels: 4,
      background,
    },
  })
    .composite([
      {
        input: symbol,
        left: Math.round((size - metadata.width) / 2),
        top: Math.round((size - metadata.height) / 2),
      },
    ])
    .png()
    .toBuffer();
}

async function roundedLauncherMaster(size = 1024) {
  const background = Buffer.from(`
    <svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}">
      <rect
        x="${Math.round(size * 0.035)}"
        y="${Math.round(size * 0.035)}"
        width="${Math.round(size * 0.93)}"
        height="${Math.round(size * 0.93)}"
        rx="${Math.round(size * 0.22)}"
        fill="#FFFFFF"
      />
    </svg>
  `);

  const symbol = await transparentSymbolCanvas(size, 0.69);

  return sharp({
    create: {
      width: size,
      height: size,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  })
    .composite([{ input: background }, { input: symbol }])
    .png()
    .toBuffer();
}

async function circularLauncherMaster(size = 1024) {
  const background = Buffer.from(`
    <svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}">
      <circle
        cx="${Math.round(size / 2)}"
        cy="${Math.round(size / 2)}"
        r="${Math.round(size * 0.465)}"
        fill="#FFFFFF"
      />
    </svg>
  `);

  const symbol = await transparentSymbolCanvas(size, 0.66);

  return sharp({
    create: {
      width: size,
      height: size,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  })
    .composite([{ input: background }, { input: symbol }])
    .png()
    .toBuffer();
}

async function monochromeSymbol(size, color, fillRatio = 0.78) {
  const transparent = await transparentSymbolCanvas(size, fillRatio);
  const alpha = await sharp(transparent).extractChannel("alpha").toBuffer();

  return sharp({
    create: {
      width: size,
      height: size,
      channels: 3,
      background: color,
    },
  })
    .joinChannel(alpha)
    .png()
    .toBuffer();
}

const symbolMaster = await transparentSymbolCanvas(1024, 0.8);
await fs.writeFile(
  path.join(brandDir, "skylink-bingwa-symbol-transparent-1024.png"),
  symbolMaster,
);

for (const size of [512, 256, 128, 64]) {
  await fs.writeFile(
    path.join(brandDir, `skylink-bingwa-symbol-transparent-${size}.png`),
    await transparentSymbolCanvas(size, 0.8),
  );
}

await fs.writeFile(
  path.join(brandDir, "skylink-bingwa-symbol-monochrome-black-1024.png"),
  await monochromeSymbol(1024, { r: 0, g: 0, b: 0 }, 0.8),
);

await fs.writeFile(
  path.join(brandDir, "skylink-bingwa-symbol-monochrome-white-1024.png"),
  await monochromeSymbol(1024, { r: 255, g: 255, b: 255 }, 0.8),
);

const launcherMaster = await roundedLauncherMaster(1024);
const roundLauncherMaster = await circularLauncherMaster(1024);
await fs.writeFile(
  path.join(androidDir, "skylink-bingwa-launcher-master-1024.png"),
  launcherMaster,
);
await fs.writeFile(
  path.join(androidDir, "skylink-bingwa-launcher-round-master-1024.png"),
  roundLauncherMaster,
);

for (const [density, size] of Object.entries(densitySizes)) {
  const directory = path.join(androidDir, `mipmap-${density}`);
  await fs.mkdir(directory, { recursive: true });
  const icon = await sharp(launcherMaster).resize(size, size).png().toBuffer();
  const roundIcon = await sharp(roundLauncherMaster)
    .resize(size, size)
    .png()
    .toBuffer();
  await fs.writeFile(path.join(directory, "ic_launcher.png"), icon);
  await fs.writeFile(path.join(directory, "ic_launcher_round.png"), roundIcon);
}

const adaptiveDir = path.join(androidDir, "adaptive");
await fs.mkdir(adaptiveDir, { recursive: true });
await fs.writeFile(
  path.join(adaptiveDir, "ic_launcher_foreground.png"),
  await transparentSymbolCanvas(432, 0.625),
);
await fs.writeFile(
  path.join(adaptiveDir, "ic_launcher_monochrome.png"),
  await monochromeSymbol(432, { r: 0, g: 0, b: 0 }, 0.625),
);
await fs.writeFile(
  path.join(adaptiveDir, "ic_launcher_background.png"),
  await sharp({
    create: {
      width: 432,
      height: 432,
      channels: 4,
      background: "#FFFFFF",
    },
  })
    .png()
    .toBuffer(),
);

for (const [density, size] of Object.entries(notificationSizes)) {
  const directory = path.join(androidDir, `drawable-${density}`);
  await fs.mkdir(directory, { recursive: true });
  await fs.writeFile(
    path.join(directory, "ic_stat_skylink_bingwa.png"),
    await monochromeSymbol(size, { r: 255, g: 255, b: 255 }, 0.78),
  );
}

await fs.writeFile(
  path.join(androidDir, "skylink-bingwa-splash-mark-512.png"),
  await transparentSymbolCanvas(512, 0.72),
);

await fs.writeFile(
  path.join(androidDir, "skylink-bingwa-play-store-512.png"),
  await solidSymbolCanvas(512, 0.7, "#FFFFFF"),
);

for (const size of [16, 32, 48]) {
  await fs.writeFile(
    path.join(webDir, `favicon-${size}x${size}.png`),
    await sharp(launcherMaster).resize(size, size).png().toBuffer(),
  );
}

await fs.writeFile(
  path.join(webDir, "apple-touch-icon-180.png"),
  await sharp(launcherMaster).resize(180, 180).png().toBuffer(),
);
await fs.writeFile(
  path.join(webDir, "android-chrome-192.png"),
  await sharp(launcherMaster).resize(192, 192).png().toBuffer(),
);
await fs.writeFile(
  path.join(webDir, "android-chrome-512.png"),
  await sharp(launcherMaster).resize(512, 512).png().toBuffer(),
);
await fs.writeFile(
  path.join(webDir, "mstile-150.png"),
  await solidSymbolCanvas(150, 0.72, "#FFFFFF"),
);

const previewIcon = await sharp(launcherMaster).resize(420, 420).png().toBuffer();
const previewLightMark = await solidSymbolCanvas(420, 0.72, "#FFFFFF");
const previewDarkMark = await solidSymbolCanvas(420, 0.72, "#0A2540");

const label = (text) =>
  Buffer.from(`
    <svg xmlns="http://www.w3.org/2000/svg" width="420" height="70">
      <text
        x="210"
        y="44"
        text-anchor="middle"
        font-family="Arial, sans-serif"
        font-size="28"
        font-weight="700"
        fill="#0A2540"
      >${text}</text>
    </svg>
  `);

const preview = await sharp({
  create: {
    width: 1480,
    height: 680,
    channels: 4,
    background: "#F6F9FC",
  },
})
  .composite([
    { input: previewIcon, left: 80, top: 90 },
    { input: previewLightMark, left: 530, top: 90 },
    { input: previewDarkMark, left: 980, top: 90 },
    { input: label("Launcher icon"), left: 80, top: 530 },
    { input: label("Transparent mark · light"), left: 530, top: 530 },
    { input: label("Transparent mark · dark"), left: 980, top: 530 },
  ])
  .png()
  .toBuffer();

await fs.writeFile(path.join(previewDir, "skylink-bingwa-logo-kit-preview.png"), preview);

console.log(
  JSON.stringify(
    {
      source: {
        width: sourceInfo.width,
        height: sourceInfo.height,
        channels: sourceInfo.channels,
      },
      extractedSymbol: {
        width: symbolMetadata.width,
        height: symbolMetadata.height,
      },
      outputRoot,
    },
    null,
    2,
  ),
);
