import datetime, pathlib, shutil, subprocess, sys

source = pathlib.Path('site')
target = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else 'site-build')
if target.exists():
    raise SystemExit(f'{target} already exists; choose a new output directory')
shutil.copytree(source, target)
revision = subprocess.check_output(['git', 'rev-parse', '--short=12', 'HEAD'], text=True).strip()
built = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M UTC')
for page in target.rglob('*.html'):
    content = page.read_text(encoding='utf-8')
    page.write_text(content.replace('<span data-build>development</span>', f'<span data-build>{revision} · built {built}</span>'), encoding='utf-8')
