import { defineConfig, type Plugin } from "vite";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { oxContent, defineTheme, defaultTheme } from "@ox-content/vite-plugin";

const siteUrl = "https://ajmx-docs.yagipass.com";
const brandColors = {
  primary: "#ccfa42",
  primaryHover: "#ddff80",
  background: "#171f27",
  backgroundAlt: "#10171d",
  text: "#e6ecef",
  textMuted: "#98a7b2",
  border: "#2b3945",
  codeBackground: "#10171d",
  codeBackgroundTop: "#10171d",
  codeText: "#e6ecef",
};

function sitemapHome(): Plugin {
  return {
    name: "ajmx-docs-sitemap-home",
    closeBundle: {
      order: "post",
      sequential: true,
      handler() {
        const file = join(import.meta.dirname, "dist/sitemap.xml");
        const loc = `<loc>${siteUrl}/</loc>`;
        const sitemap = readFileSync(file, "utf8");
        if (sitemap.includes(loc)) return;
        writeFileSync(file, sitemap.replace(/<urlset[^>]*>\n/, (urlset) => `${urlset}  <url>\n    ${loc}\n  </url>\n`));
      },
    },
  };
}

export default defineConfig({
  plugins: [
    oxContent({
      srcDir: "content",
      outDir: "dist",
      highlight: true,
      headingPermalinks: true,
      containers: {
        types: {
          terminal: { title: "Terminal" },
        },
      },
      steps: true,
      codeGroups: true,
      docs: false,
      siteMaps: true,
      ssg: {
        siteName: "ajmx",
        siteUrl,
        notFound: true,
        jsonLd: true,
        readerChrome: { copy: true, externalLinks: false, backToTop: false },
        theme: defineTheme({
          extends: defaultTheme,
          aside: true,
          header: {
            logo: "/brand/logo.svg",
            logoWidth: 61,
            logoHeight: 28,
            showSiteNameText: false,
          },
          colors: brandColors,
          darkColors: brandColors,
          fonts: {
            sans: { family: "Red Hat Text", weights: [400, 500, 600, 700], selfHost: true, fallbacks: ["sans-serif"] },
            mono: { family: "Red Hat Mono", weights: [400, 500, 600], selfHost: true, fallbacks: ["monospace"] },
            named: {
              display: { family: "Red Hat Display", weights: [500, 700, 800, 900], selfHost: true, fallbacks: ["sans-serif"] },
            },
          },
          embed: {
            head: `<link rel="icon" type="image/png" sizes="64x64" href="/favicon.png"><link rel="stylesheet" href="/brand.css"><script defer src="/brand.js"></script>
<script>try { localStorage.setItem("theme", "dark") } catch {} document.documentElement.setAttribute("data-theme", "dark")</script>`,
          },
          nav: [
            { text: "Quick start", link: "/quick-start/" },
            { text: "Use cases", link: "/use-cases/" },
            { text: "Commands", link: "/commands/" },
          ],
          socialLinks: { github: "https://github.com/yagipass/ajmx" },
          sidebar: [
            {
              text: "Get started",
              items: [
                { text: "What is ajmx?", link: "/overview.md" },
                { text: "Quick start", link: "/quick-start.md" },
                { text: "Installation", link: "/install.md" },
              ],
            },
            {
              text: "Connecting",
              items: [
                { text: "Local JVMs (--pid)", link: "/connect/local.md" },
                { text: "Remote JVMs (--url)", link: "/connect/remote.md" },
              ],
            },
            {
              text: "AI agents",
              items: [{ text: "Agent skill", link: "/agent-skill.md" }],
            },
            {
              text: "Use cases",
              items: [
                { text: "Overview", link: "/use-cases.md" },
                { text: "Heap and GC", link: "/use-cases/heap-gc.md" },
                { text: "Deadlocks and threads", link: "/use-cases/threads.md" },
                { text: "Connection pools", link: "/use-cases/connection-pool.md" },
                { text: "Changing a setting", link: "/use-cases/change-setting.md" },
                { text: "An application's own MBean", link: "/use-cases/app-mbean.md" },
              ],
            },
            {
              text: "Reference",
              items: [
                { text: "Commands", link: "/commands.md" },
                { text: "batch", link: "/batch.md" },
                { text: "Options", link: "/options.md" },
                { text: "Output", link: "/output.md" },
                { text: "Errors and exit codes", link: "/errors.md" },
              ],
            },
            {
              text: "More",
              items: [
                { text: "Troubleshooting", link: "/troubleshooting.md" },
                { text: "Limitations", link: "/limitations.md" },
                { text: "Verifying downloads", link: "/verify.md" },
              ],
            },
          ],
          footer: {
            message:
              'Released under the <a href="https://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>.',
            copyright: "Copyright 2026 yagipass",
          },
        }),
      },
    }),
    sitemapHome(),
  ],
  build: { outDir: "dist" },
});
