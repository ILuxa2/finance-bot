import copy,unittest
from deploy import payload,IDS,ENTRY
class DeployGuards(unittest.TestCase):
 def fixture(self):return {"id":"old","functionId":IDS["processor"],"runtime":"java21","entrypoint":ENTRY["processor"],"serviceAccountId":"ajefkoc36mb0t28c0i5m","resources":{"memory":"1073741824"},"executionTimeout":"180s","concurrency":"1","environment":{"EXAMPLE":"nonsecret"},"secrets":[{"id":"test","versionId":"pinned","key":"token","environmentVariable":"TOKEN"}],"tags":["$latest"],"logOptions":{"disabled":False}}
 def test_configuration_copied_without_mutation(self):
  meta=self.fixture();before=copy.deepcopy(meta);body=payload("processor",meta,"zip","0.18.15","a"*40)
  self.assertEqual(before,meta)
  for key in ("environment","secrets","resources","executionTimeout","concurrency","logOptions"):self.assertEqual(meta[key],body[key])
  self.assertNotIn("id",body);self.assertNotIn("tags",body);self.assertEqual(body["tag"],["v0-18-15-"+"a"*10])
 def test_wrong_function_or_runtime_refused(self):
  for key,value in (("functionId","foreign"),("runtime","python"),("entrypoint","other"),("serviceAccountId","other")):
   meta=self.fixture();meta[key]=value
   with self.assertRaises(RuntimeError):payload("processor",meta,"zip","0.18.15","a"*40)
if __name__=="__main__":unittest.main()
