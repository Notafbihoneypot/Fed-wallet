import concurrent.futures,hashlib,json,os,pathlib,posixpath,shutil,stat,subprocess,tarfile,tomllib,urllib.request,xml.etree.ElementTree as E,zipfile
root=pathlib.Path(__file__).resolve().parent;downloads=root/'downloads';sdk=root/'sdk';downloads.mkdir(exist_ok=True)
def fetch(url,name,expected=None,kind='sha256'):
 p=downloads/name
 if not p.exists():
  with urllib.request.urlopen(url,timeout=240) as r,p.with_suffix(p.suffix+'.part').open('wb') as f:shutil.copyfileobj(r,f)
  p.with_suffix(p.suffix+'.part').replace(p)
 if expected:assert hashlib.new(kind,p.read_bytes()).hexdigest()==expected,(name,'checksum mismatch')
 return p
for url,name in [('https://static.rust-lang.org/dist/channel-rust-1.99.1.toml','rust.toml'),('https://dl.google.com/android/repository/repository2-3.xml','android.xml'),('https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml','images.xml')]:
 fetch(url,name)
rust=tomllib.loads((downloads/'rust.toml').read_text());jobs=[]
for package,targets in [('rustc',['x86_64-unknown-linux-gnu']),('cargo',['x86_64-unknown-linux-gnu']),('rustfmt-preview',['x86_64-unknown-linux-gnu']),('clippy-preview',['x86_64-unknown-linux-gnu']),('rust-std',['x86_64-unknown-linux-gnu','aarch64-linux-android','x86_64-linux-android'])]:
 for target in targets:
  a=rust['pkg'][package]['target'][target];jobs.append(('rust',a['xz_url'],a['xz_hash'],package+'-'+target))
repo=E.parse(downloads/'android.xml').getroot();images=E.parse(downloads/'images.xml').getroot()
for pkg,dest in [('platforms;android-36',sdk/'platforms/android-36'),('build-tools;36.0.0',sdk/'build-tools/36.0.0'),('ndk;28.2.13676358',sdk/'ndk/28.2.13676358'),('platform-tools',sdk/'platform-tools'),('emulator',sdk/'emulator')]:
 options=[p for p in repo.findall('remotePackage') if p.get('path')==pkg and p.find('channelRef') is not None and p.find('channelRef').get('ref')=='channel-0'];assert options,pkg
 p=options[0];a=next(a for a in p.findall('archives/archive') if a.findtext('host-os') in [None,'linux']).find('complete')
 jobs.append(('zip','https://dl.google.com/android/repository/'+a.findtext('url'),a.findtext('checksum'),str(dest)))
p=next(p for p in images.findall('remotePackage') if p.get('path')=='system-images;android-30;default;x86_64');a=p.find('archives/archive/complete')
jobs.append(('zip','https://dl.google.com/android/repository/sys-img/android/'+a.findtext('url'),a.findtext('checksum'),str(sdk/'system-images/android-30/default/x86_64')))
jobs.append(('zip','https://services.gradle.org/distributions/gradle-8.14.6-bin.zip','7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24',str(root/'gradle-8.14.6')))
jobs.append(('jdk','https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz','8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711',str(root/'jdk')))
(root/'verified-tool-sources.json').write_text(json.dumps(jobs,indent=2))
def prepare(job):
 kind,url,h,dest=job;p=fetch(url,url.rsplit('/',1)[1],h,'sha1'if len(h)==40 else'sha256')
 print('Verified '+p.name,flush=True);return job,p
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:ready=list(pool.map(prepare,jobs))
rustprefix=root/'rust';rustprefix.mkdir(exist_ok=True)
for (kind,url,h,dest),p in ready:
 if kind=='rust':
  temp=root/('unpack-'+dest);temp.mkdir(exist_ok=True)
  with tarfile.open(p) as t:t.extractall(temp,filter='data')
  folder=next(temp.iterdir());subprocess.run(['sh','install.sh','--prefix='+str(rustprefix),'--disable-ldconfig'],cwd=folder,check=True,stdout=subprocess.DEVNULL)
  if dest.startswith('rustc-'):
   original=next(folder.rglob('librustc_driver-*.so'));shutil.copyfile(original,rustprefix/'lib'/original.name)
  shutil.rmtree(temp)
 elif kind=='jdk':
  temp=root/'jdk-unpack';temp.mkdir(exist_ok=True)
  with tarfile.open(p) as t:t.extractall(temp,filter='data')
  shutil.move(str(next(temp.iterdir())),dest);temp.rmdir()
 else:
  dest=pathlib.Path(dest)
  if dest.exists():continue
  temp=root/('unpack-'+hashlib.sha256(str(dest).encode()).hexdigest()[:10]);temp.mkdir(exist_ok=True)
  with zipfile.ZipFile(p) as z:
   z.extractall(temp)
   for m in z.infolist():
    if m.is_dir():continue
    actual=m
    while stat.S_ISLNK(actual.external_attr>>16):actual=z.getinfo(posixpath.normpath(posixpath.join(posixpath.dirname(actual.filename),z.read(actual).decode())))
    q=temp/m.filename
    if actual!=m:q.write_bytes(z.read(actual))
    mode=(actual.external_attr>>16)&0o777
    if mode:q.chmod(mode)
  folders=[x for x in temp.iterdir() if x.is_dir()];assert len(folders)==1,p
  dest.parent.mkdir(parents=True,exist_ok=True);shutil.move(str(folders[0]),str(dest));shutil.rmtree(temp)
(sdk/'licenses').mkdir(exist_ok=True);(sdk/'licenses/android-sdk-license').write_text('24333f8a63b6825ea9c5514f83c2829b004d1fee\nd56f5187479451eabf01fb78af6dfcb131a6481e\n8933bad161af4178b1185d1a37fbf41ea5269c55\n')
# Trust the runtime's managed HTTPS CA only in the build JDK.
ca=os.environ.get('SSL_CERT_FILE')
if ca:
 subprocess.run([str(root/'jdk/bin/keytool'),'-importcert','-noprompt','-alias','runtime-build-ca','-file',ca,'-keystore',str(root/'jdk/lib/security/cacerts'),'-storepass','changeit'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
print('Verified build tools installed',flush=True)
