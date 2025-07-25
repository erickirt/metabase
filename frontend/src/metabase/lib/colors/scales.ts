import { scaleLinear, scaleQuantile } from "d3-scale";

export const getColorScale = (
  extent: [number, number],
  colors: string[],
  isQuantile: boolean = false,
) => {
  if (isQuantile) {
    return scaleQuantile<string>(extent, colors);
  } else {
    const [start, end] = extent;

    const domain =
      colors.length === 3
        ? [start, start + (end - start) / 2, end]
        : [start, end];

    return scaleLinear<string>(domain, colors);
  }
};

const RGBA_REGEX =
  /rgba\((\d+\.\d+),\s*(\d+\.\d+),\s*(\d+\.\d+),\s*(\d+\.\d+)\)/;

export const getSafeColor = (color: string) => {
  return color.replace(RGBA_REGEX, (_, r, g, b, a) => {
    return `rgba(${Math.round(r)},${Math.round(g)},${Math.round(b)},${a})`;
  });
};
