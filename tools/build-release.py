#!/usr/bin/env python3
import os,pathlib,subprocess,sys
root=pathlib.Path(__file__).resolve().parents[1]
base=pathlib.Path(os.environ['UULABS_SCRATCH_DIR']).parent/'build'
base.mkdir(parents=True,exist_ok=True)
init=base/'external-build.gradle'
init.write_text("allprojects { buildDir = new File('"+str(base)+"', project.name) }\n")
env=os.environ.copy();env['JAVA_HOME']='/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home'
gradle=os.environ.get('GRADLE_BIN') or str(next((pathlib.Path.home()/'.gradle/wrapper/dists/gradle-9.2.1-bin').glob('*/gradle-9.2.1/bin/gradle')))
cmd=[gradle,'--no-daemon','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8','--project-cache-dir',str(base/'project-cache'),'-I',str(init)]+sys.argv[1:]
with (base/'build.log').open('w') as log:
 p=subprocess.Popen(cmd,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT);rc=p.wait()
print((base/'build.log').read_text()[-5500:]);sys.exit(rc)
