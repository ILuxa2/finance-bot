"""Manual GitHub OIDC deployment. Never reads finance or Lockbox payloads."""
import argparse,base64,copy,json,os,re,time,urllib.error,urllib.parse,urllib.request
from pathlib import Path
API="https://serverless-functions.api.cloud.yandex.net/functions/v1"
IDS={"reminders":"d4ekbamg45gvbp1l8b6r","processor":"d4ep8ea2jl4ldogoilcm"}
ENTRY={"reminders":"financeos.cloud.CloudReminders","processor":"financeos.cloud.CloudVkProcessor"}
FIELDS={"runtime","entrypoint","resources","executionTimeout","serviceAccountId","environment","namedServiceAccounts","concurrency","connectivity","secrets","logOptions","storageMounts","asyncInvocationConfig","tmpfsSize","mounts","metadataOptions"}
def request(url,token="",data=None,form=False):
    headers={"Content-Type":"application/x-www-form-urlencoded" if form else "application/json"}
    if token:headers["Authorization"]="Bearer "+token
    body=None if data is None else (urllib.parse.urlencode(data).encode() if form else json.dumps(data).encode())
    try:
        with urllib.request.urlopen(urllib.request.Request(url,data=body,headers=headers),timeout=90) as r:return json.load(r)
    except urllib.error.HTTPError as e:raise RuntimeError(f"Cloud request rejected (HTTP {e.code}); inspect permissions/status. Response body suppressed.") from None
    except urllib.error.URLError:raise RuntimeError("Cloud connection unavailable; no automatic retry of a version creation") from None
def identity():
    sa=os.environ.get("YC_DEPLOY_SA_ID","")
    if not re.fullmatch(r"[a-z0-9]{20}",sa):raise RuntimeError("Set repository variable YC_DEPLOY_SA_ID")
    owner=os.environ["GITHUB_REPOSITORY_OWNER"]
    url=os.environ["ACTIONS_ID_TOKEN_REQUEST_URL"]+"&audience="+urllib.parse.quote("https://github.com/"+owner,safe="")
    jwt=request(url,os.environ["ACTIONS_ID_TOKEN_REQUEST_TOKEN"])["value"]
    token=request("https://auth.yandex.cloud/oauth/token",data={"grant_type":"urn:ietf:params:oauth:grant-type:token-exchange","requested_token_type":"urn:ietf:params:oauth:token-type:access_token","audience":sa,"subject_token":jwt,"subject_token_type":"urn:ietf:params:oauth:token-type:id_token"},form=True)["access_token"]
    print("::add-mask::"+token);return token
def latest(token,function_id):
    versions=[];page=""
    while True:
        data=request(API+"/versions?"+urllib.parse.urlencode({"functionId":function_id,"pageSize":100,"pageToken":page}),token)
        versions.extend(data.get("versions",[]));page=data.get("nextPageToken","")
        if not page:break
    found=[v for v in versions if "$latest" in v.get("tags",[])]
    if len(found)!=1:raise RuntimeError("Expected exactly one latest version")
    return found[0]
def payload(component,meta,content,version,commit):
    if component not in IDS or meta.get("functionId")!=IDS[component]:raise RuntimeError("Wrong function")
    if meta.get("entrypoint")!=ENTRY[component] or meta.get("runtime")!="java21" or meta.get("serviceAccountId")!="ajefkoc36mb0t28c0i5m":raise RuntimeError("Unexpected live configuration; inspect before deployment")
    result={k:copy.deepcopy(v) for k,v in meta.items() if k in FIELDS}
    result.update(functionId=IDS[component],content=content,description=f"FinanceOS v{version}, git {commit}",tag=["v"+version.replace(".","-")+"-"+commit[:10]])
    return result
def wait(token,operation):
    deadline=time.monotonic()+300
    while not operation.get("done"):
        if time.monotonic()>deadline:raise RuntimeError("Deployment still running; check operation ID "+operation["id"])
        time.sleep(3);operation=request("https://operation.api.cloud.yandex.net/operations/"+operation["id"],token)
    if "error" in operation:raise RuntimeError("Version creation failed; cloud operation "+operation["id"])
    return operation["response"]["id"]
def main():
    parser=argparse.ArgumentParser();parser.add_argument("--version",required=True);parser.add_argument("--apply",action="store_true");args=parser.parse_args()
    if os.environ.get("GITHUB_REPOSITORY")!="ILuxa2/finance-bot" or os.environ.get("GITHUB_REF")!="refs/heads/main" or os.environ.get("GITHUB_EVENT_NAME")!="workflow_dispatch":raise RuntimeError("Production deployment is manual and main-only")
    root=Path(__file__).resolve().parents[1];build=json.loads((root/"target/ci/build.json").read_text())
    commit=os.environ["GITHUB_SHA"]
    if args.version!=build["version"] or build["commit"]!=commit or not re.fullmatch(r"\d+\.\d+\.\d+",args.version):raise RuntimeError("Version/commit confirmation mismatch")
    archive=root/"target/ci"/build["artifact"]
    import hashlib
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=build["sha256"]:raise RuntimeError("Artifact checksum mismatch")
    content=base64.b64encode(archive.read_bytes()).decode();token=identity()
    metas={c:latest(token,i) for c,i in IDS.items()}
    bodies={c:payload(c,metas[c],content,args.version,commit) for c in IDS}
    print("Verified current configuration; reminders then processor. Dry-run:",not args.apply)
    for component in IDS:
        old=metas[component]["id"]
        if not args.apply:print(component,"current",old,"unchanged");continue
        if latest(token,IDS[component])["id"]!=old:raise RuntimeError("Cloud changed since preflight; restart after review")
        operation=request(API+"/versions",token,bodies[component]);new=wait(token,operation)
        current=latest(token,IDS[component])
        if current["id"]!=new or current.get("status")!="ACTIVE":raise RuntimeError("New latest version not active; inspect before continuing")
        for key in FIELDS:
            if key in metas[component] and current.get(key)!=metas[component][key]:raise RuntimeError("Configuration changed unexpectedly: "+key)
        print(component,"active",new,"previous",old)
        with open(os.environ["GITHUB_STEP_SUMMARY"],"a",encoding="utf-8") as f:f.write(f"- {component}: `{new}`; previous `{old}`; git `{commit}`\n")
if __name__=="__main__":main()
