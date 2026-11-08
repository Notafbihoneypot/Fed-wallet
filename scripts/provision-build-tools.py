import concurrent.futures,hashlib,os,pathlib,posixpath,shutil,stat,subprocess,tarfile,urllib.request,zipfile
root=pathlib.Path(__file__).resolve().parent;downloads=root/'downloads';sdk=root/'sdk';downloads.mkdir(exist_ok=True)
def fetch(url,name,expected):
 p=downloads/name
 if not p.exists():
  with urllib.request.urlopen(url,timeout=240) as r,p.with_suffix(p.suffix+'.part').open('wb') as f:shutil.copyfileobj(r,f)
  p.with_suffix(p.suffix+'.part').replace(p)
 # Explicit raises, not assert: `python -O` strips asserts and would skip these checks.
 if hashlib.sha256(p.read_bytes()).hexdigest()!=expected:raise RuntimeError(f'{name}: checksum mismatch')
 return p
# Immutable archive URLs with committed SHA-256s; never trust a hash from a manifest
# fetched at run time. Google publishes only SHA-1: these SHA-256s were computed from
# downloads that matched Google's SHA-1 (Nov 2026). Rust/Gradle/Temurin hashes are publisher SHA-256s.
rust='https://static.rust-lang.org/dist/2026-10-15/'
android='https://dl.google.com/android/repository/'
jobs=[
 ('rust',rust+'rustc-1.99.1-x86_64-unknown-linux-gnu.tar.xz','2ff05ff9c08a90861e7f6e4065b40a8b87463b4576186fd613871c3e37f2091f','rustc-x86_64-unknown-linux-gnu'),
 ('rust',rust+'cargo-1.99.1-x86_64-unknown-linux-gnu.tar.xz','53f3521949404d595efb06a55c12c5658d4ed1c75566f33d3d514eaebe70bd8a','cargo-x86_64-unknown-linux-gnu'),
 ('rust',rust+'rustfmt-1.99.1-x86_64-unknown-linux-gnu.tar.xz','e3b36c4c7ec3486c059d8067ff48787676612007123aad7a4e8ec12c48ee9ba2','rustfmt-preview-x86_64-unknown-linux-gnu'),
 ('rust',rust+'clippy-1.99.1-x86_64-unknown-linux-gnu.tar.xz','458339712a058e52cc4522ff9ef8b59cd1ea1340312216cd94194c464a3f6dc5','clippy-preview-x86_64-unknown-linux-gnu'),
 ('rust',rust+'rust-std-1.99.1-x86_64-unknown-linux-gnu.tar.xz','109b074225bf5b9ea3820890876bfdf4ed161adbfaf94b2b4b5984c85ee5d958','rust-std-x86_64-unknown-linux-gnu'),
 ('rust',rust+'rust-std-1.99.1-aarch64-linux-android.tar.xz','7463b06bb5f734c02d3bac967538b4de4da910fb90668398bc21b10b5620e35d','rust-std-aarch64-linux-android'),
 ('rust',rust+'rust-std-1.99.1-x86_64-linux-android.tar.xz','48132cd1f40a8112345ead45e8483495ab48285f152100f3ae1b1190bed4d8d4','rust-std-x86_64-linux-android'),
 ('zip',android+'platform-36_r02.zip','37607369a28c5b640b3a7998868d45898ebcb777565a0e85f9acf36f29631d2e',str(sdk/'platforms/android-36')),
 ('zip',android+'build-tools_r36_linux.zip','5d9ac77fb6ff43d9da518a337b4fcf8f9097113df531d99ccefe80ef7ce8250b',str(sdk/'build-tools/36.0.0')),
 ('zip',android+'android-ndk-r28c-linux.zip','dfb20d396df28ca02a8c708314b814a4d961dc9074f9a161932746f815aa552f',str(sdk/'ndk/28.2.13676358')),
 ('zip',android+'platform-tools_r37.0.1-linux.zip','d230f13842f60f782a8645f9c813f8f845bf36089ea7289f28c48f17979313f1',str(sdk/'platform-tools')),
 ('zip',android+'emulator-linux_x64-16740729.zip','cbc7c3cfb83220b8a0f9c513fdab557712e74d339ec19ddffa43f23350ae9bdd',str(sdk/'emulator')),
 ('zip',android+'sys-img/android/x86_64-30_r11.zip','8d591034a4244a920d7a3ec274bb1734dd6474a3d8c11d0fce902010db3a13aa',str(sdk/'system-images/android-30/default/x86_64')),
 ('zip','https://services.gradle.org/distributions/gradle-8.14.6-bin.zip','7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24',str(root/'gradle-8.14.6')),
 ('jdk','https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz','8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711',str(root/'jdk')),
]
def prepare(job):
 kind,url,h,dest=job;p=fetch(url,url.rsplit('/',1)[1],h)
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
  folders=[x for x in temp.iterdir() if x.is_dir()]
  if len(folders)!=1:raise RuntimeError(f'{p}: expected exactly one top-level folder')
  dest.parent.mkdir(parents=True,exist_ok=True);shutil.move(str(folders[0]),str(dest));shutil.rmtree(temp)
(sdk/'licenses').mkdir(exist_ok=True);(sdk/'licenses/android-sdk-license').write_text('24333f8a63b6825ea9c5514f83c2829b004d1fee\nd56f5187479451eabf01fb78af6dfcb131a6481e\n8933bad161af4178b1185d1a37fbf41ea5269c55\n')
# Trust the runtime's managed HTTPS CA only in the build JDK.
ca=os.environ.get('SSL_CERT_FILE')
if ca:
 subprocess.run([str(root/'jdk/bin/keytool'),'-importcert','-noprompt','-alias','runtime-build-ca','-file',ca,'-keystore',str(root/'jdk/lib/security/cacerts'),'-storepass','changeit'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
print('Verified build tools installed',flush=True)
