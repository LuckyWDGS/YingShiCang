import hashlib
import io
import pathlib
import tempfile
import unittest
import uuid
from receiver import Store,CAP


class ReceiptTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.store=Store(self.temp.name)
    def test_persisted_receipt_idempotence_and_conflict(self):
        batch=str(uuid.uuid4());data=b'event\n';sha=hashlib.sha256(data).hexdigest()
        a=self.store.save(batch,sha,len(data),io.BytesIO(data))
        b=self.store.save(batch,sha,len(data),io.BytesIO(data))
        self.assertEqual(a,b);self.assertTrue(a['persisted'])
        self.assertEqual((pathlib.Path(self.temp.name)/(batch+'.log')).read_bytes(),data)
        with self.assertRaises(ValueError):self.store.save(batch,hashlib.sha256(b'other').hexdigest(),5,io.BytesIO(b'other'))
    def test_corrupt_truncated_and_oversized_never_acknowledged(self):
        batch=str(uuid.uuid4())
        for length,data in [(CAP+1,b''),(5,b'x'),(3,b'abc')]:
            with self.assertRaises(ValueError):self.store.save(batch,'0'*64,length,io.BytesIO(data))
        self.assertIsNone(self.store.receipt(batch))
        self.assertEqual(list(pathlib.Path(self.temp.name).glob('incoming-*')),[])
    def test_expiry_removes_data_and_confirmation(self):
        batch=str(uuid.uuid4());self.store.save(batch,hashlib.sha256(b'x').hexdigest(),1,io.BytesIO(b'x'))
        self.store.prune(days=-1);self.assertIsNone(self.store.receipt(batch))


if __name__=='__main__':unittest.main()
