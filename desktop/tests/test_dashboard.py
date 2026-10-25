import hashlib, io, json, math, sys, tarfile, tempfile, threading, unittest, urllib.request, urllib.error
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import numpy as np
from PIL import Image
from archive import Archive, validate_manifest, digest
from companion import Companion
from server import App, Handler, ThreadingHTTPServer
import metrics


def fixture(root, bad=False, omit=False, unsafe=False):
    pixel = np.zeros((24, 32, 3), dtype=np.uint8)
    pixel[:, :16] = (40, 100, 160)
    pixel[:, 16:] = (160, 80, 40)
    data = {}
    for name in ('comparison-original.png', 'prepared-ultrawide.png', 'baseline.png', 'result.png'):
        out = io.BytesIO(); Image.fromarray(pixel).save(out, 'PNG'); data[name] = out.getvalue()
    data['capture.json'] = json.dumps({'captureMode':'synthetic', 'syntheticFixture':True}).encode()
    data['processing.json'] = json.dumps({'aiApplied':False, 'processingDurationMs':24}).encode()
    manifest = {'protocol':1, 'sessionId':'1792913430268-abcdef', 'files':[
        {'name':name, 'bytes':len(content), 'sha256':hashlib.sha256(content).hexdigest()} for name,content in data.items()]}
    target = root / 'transfer.tar'
    with tarfile.open(target, 'w') as tar:
        for i,(name,content) in enumerate(data.items()):
            if omit and i == 0: continue
            if bad and i == 0: content = b'X' * len(content)
            info = tarfile.TarInfo('../escape' if unsafe and i == 0 else name)
            info.size = len(content); tar.addfile(info, io.BytesIO(content))
    return target, manifest, data

class MetricsTest(unittest.TestCase):
    def test_perfect_and_constant_offset(self):
        a = np.full((17,21,3),100.0); b = a + 10
        perfect = metrics.quality(a,a)
        self.assertTrue(perfect['perfectMatch']); self.assertIsNone(perfect['psnrDb'])
        self.assertAlmostEqual(perfect['ssim'],1,places=10)
        value = metrics.quality(a,b)
        self.assertAlmostEqual(value['psnrDb'],10*math.log10(255**2/100),places=10)
        self.assertAlmostEqual(value['ssim'],(2*100*110+6.5025)/(100**2+110**2+6.5025),places=10)
    def test_small_images_shape_and_delta(self):
        a=np.zeros((8,8,3)); self.assertIsNone(metrics.quality(a,a)['ssim'])
        with self.assertRaises(ValueError): metrics.quality(a,a[:4])
        self.assertEqual(metrics.change(a,a+2)['changedPixelsPercent'],100)
        self.assertEqual(metrics.change(a,a+1)['changedPixelsPercent'],0)

class ArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.root=Path(self.temp.name)
        self.archive=Archive(self.root/'archive')
    def tearDown(self): self.temp.cleanup()
    def test_verified_commit_duplicate_and_damage(self):
        tar,manifest,_=fixture(self.root)
        key,duplicate=self.archive.commit(tar,manifest,{'model':'Test phone'})
        self.assertFalse(duplicate); self.assertEqual(len(self.archive.list()),1)
        self.assertTrue(self.archive.get(key)['synthetic'])
        self.assertTrue(self.archive.commit(tar,manifest,{})[1])
        (self.archive.directory(key)/'result.png').write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError,'checksum'): self.archive.commit(tar,manifest,{})
    def test_rejected_transfers_never_indexed(self):
        for options in ({'bad':True},{'omit':True},{'unsafe':True}):
            tar,manifest,_=fixture(self.root,**options)
            with self.assertRaises(ValueError): self.archive.commit(tar,manifest,{})
            self.assertEqual(self.archive.list(),[])
            self.assertEqual(list(self.archive.incoming.iterdir()),[])
    def test_manifest_rejects_traversal_duplicate_and_missing(self):
        for kind in ('path','duplicate','missing'):
            _,manifest,_=fixture(self.root)
            if kind=='path': manifest['files'][0]['name']='../secret'
            elif kind=='duplicate': manifest['files'].append(manifest['files'][0])
            else: manifest['files']=manifest['files'][:-1]
            with self.assertRaises(ValueError): validate_manifest(manifest)
    def test_quality_registration_shape_export(self):
        tar,manifest,data=fixture(self.root); key,_=self.archive.commit(tar,manifest,{})
        with self.assertRaises(ValueError): self.archive.analyse(key,data['result.png'],False)
        out=io.BytesIO(); Image.new('RGB',(1,1)).save(out,'PNG')
        with self.assertRaises(ValueError): self.archive.analyse(key,out.getvalue(),True)
        result=self.archive.analyse(key,data['result.png'],True)
        self.assertTrue(all(row['perfectMatch'] for row in result['rows']))
        self.assertIn(b'ground_truth_sha256',self.archive.csv(key))
        self.assertTrue(self.archive.zip(key).is_file())

class FakeAdb:
    def __init__(self): self.writes=[]; self.calls=0
    def devices(self): return [{'serial':'1234567890','state':'device','model':'Test'}]
    def is_usb(self,device): return True
    def read_json(self,*args):
        self.calls+=1
        return {'protocol':1,'instanceId':'phone','elapsedRealtimeMs':self.calls*1000,'phase':'preview','transferRequest':None}
    def write_json(self,*args): self.writes.append((args[-2],args[-1]))
    def archive_stream(self,*args): raise AssertionError('Images requested without OK')

class CompanionTest(unittest.TestCase):
    def test_preview_does_not_transfer(self):
        with tempfile.TemporaryDirectory() as tmp:
            adb=FakeAdb(); companion=Companion(adb,Archive(Path(tmp)), 'com.example.referencecamera.preview')
            companion.start()
            for _ in range(60):
                if companion.snapshot()['ready']: break
                companion.stop_event.wait(.05)
            companion.stop_event.set(); companion.thread.join(3)
            self.assertTrue(companion.snapshot()['ready']); self.assertEqual(companion.archive.list(),[])
            self.assertFalse(any(name=='ack.json' for name,_ in adb.writes))
    def test_bad_manifest_never_gets_success_ack(self):
        with tempfile.TemporaryDirectory() as tmp:
            adb=FakeAdb(); companion=Companion(adb,Archive(Path(tmp)),'com.example.referencecamera.preview')
            companion.transfer({'model':'Test','serial':'1234567890'}, {'requestId':'r','appInstanceId':'p','manifestJson':'{}','manifestSha256':'wrong'})
            self.assertEqual(companion.archive.list(),[])
            self.assertFalse(adb.writes[-1][1]['success'])

class HttpTest(unittest.TestCase):
    def test_local_routes_and_cross_site_block(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive=Archive(Path(tmp)); companion=Companion(FakeAdb(),archive,'com.example.referencecamera.preview')
            server=ThreadingHTTPServer(('127.0.0.1',0),Handler); server.daemon_threads=True
            port=server.server_address[1]; app=App(archive,companion,port); server.app=app
            thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
            base='http://127.0.0.1:'+str(port)
            try:
                self.assertEqual(urllib.request.urlopen(base+'/').status,200)
                self.assertEqual(json.load(urllib.request.urlopen(base+'/api/sessions')),[])
                for request in (urllib.request.Request(base+'/api/state',headers={'Origin':'https://evil.example'}),
                                urllib.request.Request(base+'/api/settings',data=b'{"paused":true}',headers={'Origin':'https://evil.example'})):
                    with self.assertRaises(urllib.error.HTTPError) as error: urllib.request.urlopen(request)
                    self.assertEqual(error.exception.code,403)
                with self.assertRaises(urllib.error.HTTPError) as error: urllib.request.urlopen(base+'/%2e%2e/server.py')
                self.assertEqual(error.exception.code,404)
                self.assertFalse(companion.paused)
            finally:
                app.stopping.set(); server.shutdown(); server.server_close(); app.pool.shutdown(); thread.join()

if __name__=='__main__': unittest.main()
