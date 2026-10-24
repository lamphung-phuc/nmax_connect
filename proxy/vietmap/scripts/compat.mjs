// The app's map engine predates the `slice` expression; the editor writes "name starts with" as
// ["==", ["slice", x, 0, n], "s"]. `in` (contains) is the closest it supports: looser, but it only
// sits in POI icon filters and sort order.
export function startsWithToIn(v) {
  if (Array.isArray(v)) {
    if (v[0] === '==' && v[1]?.[0] === 'slice' && typeof v[2] === 'string') return ['in', v[2], startsWithToIn(v[1][1])];
    return v.map(startsWithToIn);
  }
  return v && typeof v === 'object' ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, startsWithToIn(x)])) : v;
}

// The editor writes a solid line as `line-dasharray: [0]`; the app's engine wants two numbers and drops the layer.
export function dropShortDashes(layers) {
  for (const l of layers) {
    const dash = l.paint?.['line-dasharray'];
    if (Array.isArray(dash) && dash.length < 2) delete l.paint['line-dasharray'];
  }
  return layers;
}
