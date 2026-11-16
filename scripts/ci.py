"""Portable executable-test runner and binary packaging. No cloud credentials required."""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def tool(name):
    candidates={"java":"../toolchain/jdk21/jdk21.0.12_12/bin/java.exe","jar":"../toolchain/jdk21/jdk21.0.12_12/bin/jar.exe","mvn":"../toolchain/maven/apache-maven-3.10.0/bin/mvn.cmd"}
    path=ROOT/candidates[name]
    if path.exists():return str(path.resolve())
    found=shutil.which(name)
    if found:return found
    raise RuntimeError(f"Install {name} (Java 21 and Maven required)")
def run(args):subprocess.run(args,cwd=ROOT,check=True)
def main():
    parser=argparse.ArgumentParser();parser.add_argument("--no-compile",action="store_true");args=parser.parse_args()
    if not args.no_compile:
        run([tool("mvn"),"-B","-q","-Dmaven.repo.local=.m2","test-compile","org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies","-DincludeGroupIds=com.fasterxml.jackson.core","-DoutputDirectory=target/dependency"])
    jars=sorted((ROOT/"target/dependency").glob("*.jar")) or sorted((ROOT/".m2/com/fasterxml/jackson").rglob("*.jar"))
    if not jars:raise RuntimeError("Jackson runtime dependencies missing")
    source=(ROOT/"build.ps1").read_text(encoding="utf-8-sig")
    tests=list(dict.fromkeys(re.findall(r"(?m)^\s*& \$java -cp \$classpath (financeos\.[A-Za-z0-9_.]+Test)\s*$",source)))
    if not tests:raise RuntimeError("No registered test classes")
    classpath=os.pathsep.join(map(str,[ROOT/"target/test-classes",ROOT/"target/classes",*jars]))
    for test in tests:
        print("RUN",test,flush=True);run([tool("java"),"-Djava.awt.headless=true","-cp",classpath,test])
    import xml.etree.ElementTree as ET
    version=ET.parse(ROOT/"pom.xml").findtext("{*}version")
    out=ROOT/"target/ci";out.mkdir(parents=True,exist_ok=True)
    jar=out/"financeos.jar";run([tool("jar"),"--create","--file",str(jar),"-C",str(ROOT/"target/classes"),"."])
    archive=out/f"FinanceOS-Java-VK-v{version}-binary.zip"
    with zipfile.ZipFile(archive,"w",zipfile.ZIP_DEFLATED) as z:
        for path in [jar,*jars]:z.write(path,path.name)
    report={"version":version,"commit":os.environ.get("GITHUB_SHA","local"),"tests":len(tests),"sha256":hashlib.sha256(archive.read_bytes()).hexdigest(),"artifact":archive.name}
    (out/"build.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print(f"Passed {len(tests)} test classes; built {archive.name}")
if __name__=="__main__":main()
