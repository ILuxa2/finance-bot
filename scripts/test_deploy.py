import copy,unittest
from deploy import payload,IDS,ENTRY,rejection_category,CloudRequestRejected,config_equal,FOLDER
class DeployGuards(unittest.TestCase):
 def fixture(self):return {"id":"old","functionId":IDS["processor"],"runtime":"java21","entrypoint":ENTRY["processor"],"serviceAccountId":"ajefkoc36mb0t28c0i5m","resources":{"memory":"1073741824"},"executionTimeout":"180s","concurrency":"1","environment":{"EXAMPLE":"nonsecret"},"secrets":[{"id":"test","versionId":"pinned","key":"token","environmentVariable":"TOKEN"}],"tags":["$latest"],"logOptions":{"disabled":False}}
 def test_configuration_copied_without_mutation(self):
  meta=self.fixture();before=copy.deepcopy(meta);body=payload("processor",meta,"zip","0.18.15","a"*40)
  self.assertEqual(before,meta)
  for key in ("environment","secrets","resources","executionTimeout","concurrency","logOptions"):self.assertEqual(meta[key],body[key])
  self.assertNotIn("id",body);self.assertNotIn("tags",body);self.assertNotIn("tag",body);self.assertEqual(body["description"],"FinanceOS v0.18.15, git "+"a"*40)
 def test_wrong_function_or_runtime_refused(self):
  for key,value in (("functionId","foreign"),("runtime","python"),("entrypoint","other"),("serviceAccountId","other")):
   meta=self.fixture();meta[key]=value
   with self.assertRaises(RuntimeError):payload("processor",meta,"zip","0.18.15","a"*40)
 def test_provider_error_is_classified_without_leaking_message(self):
  raw=b'{"message":"Request payload too large: private-secret-value"}'
  category=rejection_category(raw)
  self.assertEqual(category,"request-size")
  self.assertNotIn("private-secret-value",str(CloudRequestRejected(429,category)))
 def test_log_defaults_are_semantic_but_destination_and_level_are_strict(self):
  self.assertTrue(config_equal("logOptions",{"folderId":"same"},{"folderId":"same","disabled":False,"minLevel":"LEVEL_UNSPECIFIED"}))
  self.assertFalse(config_equal("logOptions",{"folderId":"same"},{"folderId":"other"}))
  self.assertFalse(config_equal("logOptions",{"folderId":"same"},{"folderId":"same","disabled":True}))
  self.assertFalse(config_equal("logOptions",{"minLevel":"WARN"},{"minLevel":"INFO"}))
  self.assertFalse(config_equal("environment",{"FLAG":"1"},{"FLAG":"2"}))
 def test_implicit_logging_destination_is_only_the_function_folder(self):
  self.assertTrue(config_equal("logOptions",{"disabled":False},{"disabled":False,"folderId":FOLDER}))
  self.assertFalse(config_equal("logOptions",{"disabled":False},{"disabled":False,"folderId":"another-folder"}))
  self.assertFalse(config_equal("logOptions",{}, {"folderId":FOLDER,"logGroupId":"custom"}))
 def test_full_tag_quota_does_not_require_new_tag(self):
  meta=self.fixture();meta["tags"]=["$latest"]+["v-old-"+str(i) for i in range(10)]
  body=payload("processor",meta,"zip","0.18.16","a"*40)
  self.assertNotIn("tag",body);self.assertNotIn("tags",body)
  self.assertEqual(rejection_category(b'{"message":"Function tags quota exceeded"}'),"tag-quota")
 def test_rate_and_version_quota_are_distinct(self):
  self.assertEqual(rejection_category(b'{"message":"Rate limit exceeded"}'),"rate-limit")
  self.assertEqual(rejection_category(b'{"message":"Total size of all versions exceeds limit"}'),"version-storage-quota")
  self.assertEqual(rejection_category(b'not json'),"unknown")
if __name__=="__main__":unittest.main()
