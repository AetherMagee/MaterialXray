// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

const repo = 'https://github.com/AetherMagee/MaterialXray';

export default defineConfig({
	site: 'https://materialxray.app',
	integrations: [
		starlight({
			title: 'Material Xray',
			description: 'An Android proxy client powered by Xray-core, with a Material Design 3 interface.',
			logo: { src: './src/assets/icon.png', alt: 'Material Xray' },
			favicon: '/favicon.png',
			social: [{ icon: 'github', label: 'GitHub', href: repo }],
			customCss: ['./src/styles/tokens.css', './src/styles/docs.css'],
			head: [
				// The docs have their own dark theme, so Dark Reader stays off.
				{ tag: 'meta', attrs: { name: 'darkreader-lock' } },
				{ tag: 'meta', attrs: { property: 'og:image', content: 'https://materialxray.app/og.jpg' } },
				{ tag: 'meta', attrs: { property: 'og:image:width', content: '1200' } },
				{ tag: 'meta', attrs: { property: 'og:image:height', content: '630' } },
				{ tag: 'link', attrs: { rel: 'preconnect', href: 'https://fonts.googleapis.com' } },
				{ tag: 'link', attrs: { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: true } },
				{
					tag: 'link',
					attrs: {
						rel: 'stylesheet',
						href: 'https://fonts.googleapis.com/css2?family=Google+Sans+Code:wght@400;500&family=Google+Sans+Flex:opsz,wght@6..144,400..800&display=swap',
					},
				},
			],
			expressiveCode: {
				themes: ['github-dark-default', 'github-light-default'],
				styleOverrides: {
					borderRadius: '16px',
					borderColor: 'var(--md-outline-variant)',
					codeFontFamily: 'var(--md-font-code)',
					uiFontFamily: 'var(--md-font)',
					codeBackground: 'var(--md-surface-container-low)',
					frames: {
						editorTabBarBackground: 'var(--md-surface-container)',
						editorActiveTabBackground: 'var(--md-surface-container-low)',
						terminalTitlebarBackground: 'var(--md-surface-container)',
						terminalBackground: 'var(--md-surface-container-low)',
						frameBoxShadowCssValue: 'none',
					},
				},
			},
			sidebar: [
				{
					label: 'Start here',
					items: [
						{ label: 'Introduction', slug: 'docs' },
						'docs/guide/installation',
						'docs/guide/adding-servers',
						'docs/guide/root-vs-rootless',
					],
				},
				{
					label: 'Using the app',
					items: [
						'docs/guide/per-app-proxy',
						'docs/guide/routing',
						'docs/guide/settings',
						'docs/guide/troubleshooting',
					],
				},
				{
					label: 'For providers',
					items: [
						{ label: 'Overview', slug: 'docs/providers' },
						'docs/providers/subscription-format',
						'docs/providers/request-headers',
						'docs/providers/response-headers',
						'docs/providers/routing-header',
						'docs/providers/per-app-routing',
						'docs/providers/deeplinks',
					],
				},
				{
					label: 'Reference',
					items: ['docs/reference/share-links', 'docs/reference/faq'],
				},
			],
		}),
	],
});
