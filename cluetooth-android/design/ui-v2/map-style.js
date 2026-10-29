/*
 * Cluetooth map style: the sources and layers drawn over the base map, as plain JSON. No DOM markers.
 * Colors and sizes come in as values (the app reads them from tokens.css), so the result can be serialized
 * unchanged to a .json file and loaded by MapLibre Android (Style.Builder / addSource / addLayer, or
 * JSON.stringify(CluetoothMapStyle.layers(colors)) into a style fragment).
 *
 * Sources (GeoJSON, data set at runtime):
 *   route      the session's route (LineString features; All sessions: several)
 *   ends       Start and End points: properties { kind: "start" | "end", label: "Start" | "End" }
 *   density    one point per device (the density wash)
 *   devices    one point per device, clustered: properties { addr }
 *   sightings  the selected device's sightings: properties { rssi }
 *   me         the user's location (shown when there is no session)
 * Selection is a filter on "point-selected" plus a data-driven opacity on "points" (selectionProps).
 */
(function () {
  "use strict";

  const empty = { type: "FeatureCollection", features: [] };

  /** The GeoJSON sources, empty. */
  const sources = () => ({
    route: { type: "geojson", data: empty },
    ends: { type: "geojson", data: empty },
    density: { type: "geojson", data: empty },
    devices: { type: "geojson", data: empty, cluster: true, clusterRadius: 50, clusterMaxZoom: 18 },
    sightings: { type: "geojson", data: empty },
    me: { type: "geojson", data: empty },
  });

  /**
   * The layers, bottom to top. c: {
   *   route, routeOpacity, density, densityOpacity, densityRadius, point, stroke, clusterText,
   *   rssiStrong, rssiFair, rssiWeak, start, end, endText, halo, selected, me, font
   * }
   */
  const layers = (c) => [
    { id: "route", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": c.route, "line-width": 4, "line-opacity": c.routeOpacity } },
    { id: "density", type: "circle", source: "density",
      paint: { "circle-radius": c.densityRadius, "circle-color": c.density, "circle-opacity": c.densityOpacity } },
    { id: "clusters", type: "circle", source: "devices", filter: ["has", "point_count"],
      paint: {
        "circle-color": c.point, "circle-stroke-color": c.stroke, "circle-stroke-width": 2,
        // sized by count in a few steps
        "circle-radius": ["step", ["get", "point_count"], 13, 10, 16, 50, 20, 200, 24],
        "circle-opacity": 1, "circle-stroke-opacity": 1,
      } },
    { id: "cluster-count", type: "symbol", source: "devices", filter: ["has", "point_count"],
      layout: { "text-field": ["get", "point_count_abbreviated"], "text-font": [c.font], "text-size": 12,
        "text-allow-overlap": true, "text-ignore-placement": true },
      paint: { "text-color": c.clusterText, "text-opacity": 1 } },
    { id: "points", type: "circle", source: "devices", filter: ["!", ["has", "point_count"]],
      paint: { "circle-radius": 5, "circle-color": c.point, "circle-stroke-color": c.stroke, "circle-stroke-width": 1.5,
        "circle-opacity": 1, "circle-stroke-opacity": 1 } },
    { id: "sightings", type: "circle", source: "sightings",
      paint: { "circle-radius": 6, "circle-stroke-width": 2, "circle-stroke-color": c.stroke,
        "circle-color": ["step", ["get", "rssi"], c.rssiWeak, -80, c.rssiFair, -65, c.rssiStrong] } },
    { id: "point-selected", type: "circle", source: "devices", filter: ["==", ["get", "addr"], ""],
      paint: { "circle-radius": 8, "circle-color": c.selected, "circle-stroke-color": c.stroke, "circle-stroke-width": 2 } },
    { id: "me", type: "circle", source: "me",
      paint: { "circle-radius": 8, "circle-color": c.me, "circle-stroke-color": c.stroke, "circle-stroke-width": 3 } },
    // Start and End: a small dot and a label with a halo, above everything else
    { id: "end-dots", type: "circle", source: "ends",
      paint: { "circle-radius": 5, "circle-color": ["match", ["get", "kind"], "start", c.start, c.end],
        "circle-stroke-color": c.stroke, "circle-stroke-width": 2 } },
    { id: "end-labels", type: "symbol", source: "ends",
      layout: { "text-field": ["get", "label"], "text-font": [c.font], "text-size": 12,
        "text-anchor": ["match", ["get", "kind"], "start", "bottom", "top"],
        "text-offset": ["match", ["get", "kind"], "start", ["literal", [0, -0.8]], ["literal", [0, 0.8]]],
        "text-allow-overlap": true, "text-ignore-placement": true },
      paint: { "text-color": ["match", ["get", "kind"], "start", c.start, c.endText], "text-halo-color": c.halo, "text-halo-width": 2 } },
  ];

  /**
   * The selection, as property values to set: [layerId, kind ("paint" | "filter"), property, value].
   * addr null clears it. dim is the opacity of everything that is not the selected device.
   */
  const selectionProps = (addr, dim) => {
    const fade = addr ? ["case", ["==", ["get", "addr"], addr], 1, dim] : 1;
    const other = addr ? dim : 1;
    return [
      ["points", "paint", "circle-opacity", fade],
      ["points", "paint", "circle-stroke-opacity", fade],
      ["clusters", "paint", "circle-opacity", other],
      ["clusters", "paint", "circle-stroke-opacity", other],
      ["cluster-count", "paint", "text-opacity", other],
      ["point-selected", "filter", null, ["==", ["get", "addr"], addr || ""]],
    ];
  };

  /** Start and End of a route as features for the "ends" source. */
  const endsOf = (route) => ({
    type: "FeatureCollection",
    features: route.length > 1 ? [
      { type: "Feature", properties: { kind: "start", label: "Start" }, geometry: { type: "Point", coordinates: route[0] } },
      { type: "Feature", properties: { kind: "end", label: "End" }, geometry: { type: "Point", coordinates: route[route.length - 1] } },
    ] : [],
  });

  window.CluetoothMapStyle = { sources, layers, selectionProps, endsOf };
})();
