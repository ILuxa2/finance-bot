package financeos.vk;
import java.net.*;import java.net.http.*;import java.io.*;import java.time.Duration;import java.util.*;import java.util.function.Supplier;import com.fasterxml.jackson.databind.*;
/** Bounded, redirect-free download from VK image hosts, then Vision OCR. */
public final class YandexReceiptReader implements ReceiptReader {
 private static final class LocalHttp {static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();}
 public static ReceiptReader local(){if(System.getenv("FINANCEOS_OCR_FOLDER_ID")==null||System.getenv("FINANCEOS_OCR_API_KEY")==null)return event->{throw new IOException("OCR is not configured");};return configured(LocalHttp.CLIENT,null);}
 private final HttpClient http;private final URI endpoint;private final Supplier<String> authorization;private final String folder;
 public YandexReceiptReader(HttpClient http,URI endpoint,Supplier<String> authorization,String folder){this.http=http;this.endpoint=endpoint;this.authorization=authorization;this.folder=folder;if(http.followRedirects()!=HttpClient.Redirect.NEVER)throw new IllegalArgumentException("Receipt downloads must not follow redirects");}
 public static ReceiptReader configured(HttpClient http,Supplier<String> iam){
  String folder=System.getenv("FINANCEOS_OCR_FOLDER_ID"),key=System.getenv("FINANCEOS_OCR_API_KEY");
  if(folder==null||folder.isBlank()||(iam==null&&(key==null||key.isBlank())))return event->{throw new IOException("OCR is not configured");};
  return new YandexReceiptReader(http,URI.create("https://ai.api.cloud.yandex.net/ocr/v1/recognizeText"),iam!=null?()->"Bearer "+iam.get():()->"Api-Key "+key,folder);
 }
 public static boolean allowedPhoto(URI uri){String host=uri.getHost();return "https".equalsIgnoreCase(uri.getScheme())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&host!=null&&(host.endsWith(".userapi.com")||host.endsWith(".vkuserphoto.ru")||host.endsWith(".vk.com"));}
 @Override public String read(VkEvent event)throws IOException,InterruptedException{
  if(event.photoUrls().size()!=1)throw new IOException("Send exactly one receipt photo");URI photo;
  try{photo=URI.create(event.photoUrls().getFirst());}catch(IllegalArgumentException invalid){throw new IOException("Invalid photo URL");}
  if(!allowedPhoto(photo))throw new IOException("Unsupported photo host");
  var response=http.send(HttpRequest.newBuilder(photo).timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;
  try(var stream=response.body()){if(response.statusCode()!=200)throw new IOException("Receipt download failed");bytes=stream.readNBytes(8*1024*1024+1);}
  return recognize(bytes);
 }
 public String recognize(byte[] bytes)throws IOException,InterruptedException{
  if(bytes.length==0||bytes.length>8*1024*1024)throw new IOException("Receipt photo too large");String mime;
  try(var input=javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
   var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Invalid receipt image");var reader=readers.next();
   try{reader.setInput(input);long pixels=(long)reader.getWidth(0)*reader.getHeight(0);if(pixels<=0||pixels>20000000)throw new IOException("Image pixel limit");String format=reader.getFormatName();mime=format.equalsIgnoreCase("JPEG")?"JPEG":format.equalsIgnoreCase("PNG")?"PNG":"";if(mime.isEmpty())throw new IOException("Only JPEG and PNG supported");}finally{reader.dispose();}
  }
  var mapper=new ObjectMapper();byte[] body=mapper.writeValueAsBytes(Map.of("mimeType",mime,"languageCodes",List.of("ru","en"),"model","page","content",Base64.getEncoder().encodeToString(bytes)));
  var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30)).header("Authorization",authorization.get()).header("x-folder-id",folder).header("x-data-logging-enabled","false").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
  var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());byte[] json;
  try(var stream=response.body()){if(response.statusCode()!=200)throw new IOException("OCR HTTP "+response.statusCode());json=stream.readNBytes(2*1024*1024+1);}
  if(json.length>2*1024*1024)throw new IOException("OCR result too large");var root=mapper.readTree(json);if(root==null)throw new IOException("Empty OCR result");var result=root.has("result")?root.path("result"):root;String text=result.path("textAnnotation").path("fullText").asText("");if(text.isBlank()||text.length()>50000)throw new IOException("Receipt text missing");return text;
 }
}
