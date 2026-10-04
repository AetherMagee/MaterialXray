# Material Xray website

Landing page and documentation for [Material Xray](https://github.com/AetherMagee/MaterialXray), built with [Astro](https://astro.build) and [Starlight](https://starlight.astro.build) and styled with Material 3 tokens.

```sh
npm install
npm run dev      # http://localhost:4321
npm run build    # static output in dist/
```

## Layout

```text
src/pages/index.astro        Landing page
src/content/docs/docs/       Documentation, served under /docs/
src/styles/tokens.css        Material 3 color, shape and type tokens (light + dark)
src/styles/landing.css       Landing page components
src/styles/docs.css          Starlight → Material 3 mapping
```

Both the landing page and the docs keep the theme in Starlight's `starlight-theme` localStorage key, so a choice made on one carries over to the other.

## Deployment

`.github/workflows/website.yml` builds the site on every push to master that touches `website/` and rsyncs `dist/` to the server. Its key only runs `rrsync` confined to the site's directory there. Pull requests build without deploying, and the workflow can also be run by hand.
