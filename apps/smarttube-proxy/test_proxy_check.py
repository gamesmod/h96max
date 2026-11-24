import unittest
import proxy_check as p

class ProxyTests(unittest.TestCase):
    def test_dedup(self):
        self.assertEqual(p.parse(b"8.8.8.8:1080\nsocks5://8.8.8.8:1080\n127.0.0.1:80\nhttp://8.8.4.4:80\n", "socks5", "txt"),
                         {("socks5","8.8.8.8",1080)})
    def test_invalid(self):
        for line in ("10.0.0.1:80","169.254.169.254:80","8.8.8.8:0","8.8.8.8:65536","foo:80","8.8.8.8:80@evil"):
            self.assertIsNone(p.normalize(line,"http"))
    def test_json(self):
        self.assertEqual(p.parse(b'{"data":[{"ip":"8.8.8.8","port":1080,"protocols":["socks5"]}]}',"socks5","geonode"),
                         {("socks5","8.8.8.8",1080)})
    def test_media_url(self):
        self.assertTrue(p.media_valid("https://r1.googlevideo.com/videoplayback?x=1"))
        self.assertFalse(p.media_valid("https://googlevideo.com/"))
        self.assertFalse(p.media_valid("https://r1.googlevideo.com.evil.test/videoplayback?x=1"))
    def test_media_status(self):
        self.assertEqual(p.classify_media((0,206,65536,100,12,"video/mp4"),10),"WORKING")
        self.assertEqual(p.classify_media((0,206,65536,100,2,"video/mp4"),10),"SLOW")
        self.assertEqual(p.classify_media((0,200,65536,100,12,"text/html"),10),"CDN_BLOCKED")
        self.assertEqual(p.classify_media((60,0,0,0,0,""),10),"TLS_ERROR")

if __name__ == "__main__":
    unittest.main()
