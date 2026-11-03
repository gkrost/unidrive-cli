import pathlib, sys
from html.parser import HTMLParser
from urllib.parse import urlsplit, unquote

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else 'site').resolve()
errors = []
class Page(HTMLParser):
    def __init__(self):
        super().__init__(); self.ids = set(); self.links = []
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if 'id' in a: self.ids.add(a['id'])
        for key in ('href', 'src'):
            if key in a: self.links.append(a[key])
pages = {}
for f in root.rglob('*.html'):
    p = Page(); p.feed(f.read_text(encoding='utf-8')); pages[f] = p
for required in ('index.html', '404.html', 'install/index.html', 'install/unidrive-releases.gpg', '.well-known/security.txt', '.nojekyll'):
    if not (root / required).is_file(): errors.append('Missing: ' + required)
for file, page in pages.items():
    for link in page.links:
        url = urlsplit(link)
        if url.scheme or url.netloc or link.startswith('//'): continue
        path = unquote(url.path)
        target = (root / path.lstrip('/') if path.startswith('/') else file.parent / path).resolve() if path else file
        if not target.is_relative_to(root): errors.append(f'{file}: outside site: {link}'); continue
        if target.is_dir(): target = target / 'index.html'
        if not target.is_file(): errors.append(f'{file}: missing: {link}'); continue
        if url.fragment and target in pages and unquote(url.fragment) not in pages[target].ids:
            errors.append(f'{file}: missing fragment: {link}')
if errors:
    print('\n'.join(errors)); sys.exit(1)
print(f'Checked {len(pages)} pages and required files')
