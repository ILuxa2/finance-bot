package financeos.vk;
import com.fasterxml.jackson.databind.*;import com.sun.net.httpserver.HttpServer;import java.net.*;import java.net.http.*;import java.util.*;import java.io.*;import java.awt.image.BufferedImage;import javax.imageio.ImageIO;import java.util.concurrent.atomic.*;
public final class ReceiptOcrTest {
 static void ok(boolean value,String why){if(!value)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  var mapper=new ObjectMapper();var parser=new VkEventParser(99,"secret");
  var photo=Map.of("type","photo","photo",Map.of("sizes",List.of(Map.of("url","https://sun9-1.userapi.com/small.jpg","width",100,"height",200),Map.of("url","https://sun9-1.userapi.com/large.jpg","width",500,"height",1000))));
  var message=Map.of("from_id",42,"peer_id",42,"id",1,"text","","attachments",List.of(photo));
  var event=parser.parse(mapper.writeValueAsBytes(Map.of("secret","secret","group_id",99,"type","message_new","object",Map.of("message",message))));ok(event.photoUrls().equals(List.of("https://sun9-1.userapi.com/large.jpg")),"Largest photo not preserved");
  ok(mapper.readValue(mapper.writeValueAsBytes(event),VkEvent.class).equals(event),"Photo lost in inbox serialization");ok(mapper.readValue("{\"type\":\"message_new\",\"groupId\":99,\"userId\":42,\"peerId\":42,\"eventId\":\"old\",\"text\":\"/start\"}",VkEvent.class).photoUrls().isEmpty(),"Old inbox events incompatible");
  for(String url:List.of("http://sun9-1.userapi.com/a","https://127.0.0.1/a","https://169.254.169.254/a","https://userapi.com.evil.example/a","https://x.userapi.com:123/a","https://name@x.userapi.com/a"))ok(!YandexReceiptReader.allowedPhoto(URI.create(url)),"Unsafe photo source accepted");
  var wrapped=new AtomicBoolean(true);var status=new AtomicInteger(200);var calls=new AtomicInteger();var valid=new AtomicBoolean();var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
  server.createContext("/ocr",x->{try{calls.incrementAndGet();var json=mapper.readTree(x.getRequestBody());valid.set(x.getRequestHeaders().getFirst("Authorization").equals("Bearer dummy")&&x.getRequestHeaders().getFirst("x-folder-id").equals("folder")&&x.getRequestHeaders().getFirst("x-data-logging-enabled").equals("false")&&json.path("mimeType").asText().equals("PNG")&&!json.path("content").asText().isBlank());var annotation=Map.of("textAnnotation",Map.of("fullText","Магазин\nИТОГ 100.00"));byte[] response=mapper.writeValueAsBytes(wrapped.get()?Map.of("result",annotation):annotation);x.sendResponseHeaders(status.get(),response.length);x.getResponseBody().write(response);}finally{x.close();}});server.start();
  try{
   var client=new YandexReceiptReader(HttpClient.newHttpClient(),URI.create("http://localhost:"+server.getAddress().getPort()+"/ocr"),()->"Bearer dummy","folder");var image=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB),"png",image);
   ok(client.recognize(image.toByteArray()).contains("ИТОГ 100.00")&&valid.get(),"OCR wire contract failed");
   wrapped.set(false);ok(client.recognize(image.toByteArray()).contains("ИТОГ 100.00"),"Direct OCR shape failed");
   for(int code:List.of(403,429,503)){status.set(code);try{client.recognize(image.toByteArray());throw new AssertionError("OCR error accepted");}catch(IOException expected){ok(expected.getMessage().equals("OCR HTTP "+code),"Unsafe OCR error detail");}}
   int before=calls.get();for(byte[] invalid:List.of(new byte[0],new byte[]{1,2,3},new byte[8*1024*1024+1])){try{client.recognize(invalid);throw new AssertionError("Invalid photo accepted");}catch(IOException expected){}}ok(calls.get()==before,"Invalid image sent to OCR");
   try{client.read(new VkEvent("message_new",99,42,42,"unsafe","",0,List.of("https://169.254.169.254/metadata")));throw new AssertionError("Metadata request allowed");}catch(IOException expected){}
   ok(calls.get()==before,"Blocked source called OCR");
  }finally{server.stop(0);}
  System.out.println("Receipt OCR: attachments/inbox compatibility, bounded images, SSRF guards, Vision contract and failure codes passed (localhost only).");
 }
}
