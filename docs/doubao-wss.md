
- 在 chrome network 面板中抓取到 doubao 语音输入时调用的 wss api:
```
curl 'wss://ws-samantha.doubao.com/samantha/audio/asr' \
  -H 'Upgrade: websocket' \
  -H 'Origin: https://www.doubao.com' \
  -H 'Cache-Control: no-cache' \
  -H 'Accept-Language: zh-CN,zh;q=0.9,en;q=0.8' \
  -H 'Pragma: no-cache' \
  -H 'Connection: Upgrade' \
  -H 'Sec-WebSocket-Key: ' \
  -H 'User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36' \
  -H 'Sec-WebSocket-Version: 13' \
  -H 'Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits'
```

- 这是 doubao wss 接口返回的数据例子：

```jsonl
{"event":"result","result":{"Text":"如何判断电脑 DNS 设置是否成功？"},"code":0,"message":""}	79	
12:34:46.602
{"event":"result","result":{"Text":"如何判断电脑 DNS 设置是否成功？"},"code":0,"message":""}	79	
12:34:46.649
{"event":"result","result":{"Text":"如何判断电脑 DNS 设置是否成功？"},"code":0,"message":""}	79	
12:34:46.649
{"event":"result","result":{"Text":"如何判断电脑 DNS 设置是否成功？"},"code":0,"message":""}	79	
12:34:46.656
{"event":"result","result":{"Text":"如何判断电脑 DNS 设置是否成功？"},"code":0,"message":""}	79	
12:34:46.772
{"event":"result","result":{"Text":"如何判断电脑 DNS 设置是否成功？"},"code":0,"message":""}	79	
12:34:46.775
{"event":"finish","result":null,"code":0,"message":""}
```