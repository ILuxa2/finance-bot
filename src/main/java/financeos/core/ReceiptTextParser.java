package financeos.core;
import java.time.*;import java.time.format.*;import java.util.*;import java.util.regex.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
/** Conservative extraction: never infer the total from the largest number on a receipt. */
public final class ReceiptTextParser {
 public record Parsed(String total,String date,String store,String fingerprint){}
 public Parsed parse(String text,LocalDate today){
  if(text==null||text.isBlank()||text.length()>50000)throw new IllegalArgumentException("Receipt text missing");
  String clean=text.replace('\u00a0',' ').replace('\u202f',' ');var totals=new LinkedHashSet<String>();
  // OCR may place '=' or currency on separate lines. Never scan past another text label.
  var pattern=Pattern.compile("(?imu)^\\h*(?:ИТОГ(?:О)?(?:\\h+К\\h+ОПЛАТЕ)?|К\\h+ОПЛАТЕ|СУММА\\h+(?:К\\h+ОПЛАТЕ|РАСЧ[ЕЁ]ТА|ПО\\h+ЧЕКУ)|ВСЕГО\\h+К\\h+ОПЛАТЕ|TOTAL(?:\\h+DUE)?)\\h*(?:[:=]\\h*)*\\h*(?:\\R\\h*(?:[:=]\\h*)*\\h*){0,2}(?:(?:₽|РУБ\\.?|RUB)\\h*)?((?:[0-9]{1,3}(?: [0-9]{3})+|[0-9]+)(?:[.,][0-9]{1,2})?)(?:\\h*(?:₽|РУБ\\.?|RUB))?\\h*$");
  var m=pattern.matcher(clean);while(m.find()){var amount=Money.of(m.group(1).replace(" ","").replace(',','.'));if(amount.precision()>14)throw new IllegalArgumentException("Receipt total too large");if(amount.signum()>0)totals.add(amount.toPlainString());}
  if(totals.size()!=1)throw new IllegalArgumentException("Receipt total ambiguous or missing");
  String date=today.toString();var dates=Pattern.compile("(?<![0-9])([0-3][0-9]\\.[01][0-9]\\.[12][0-9]{3})(?![0-9])").matcher(clean);
  if(dates.find())try{date=LocalDate.parse(dates.group(1),DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)).toString();}catch(DateTimeException invalid){throw new IllegalArgumentException("Receipt date invalid");}
  String store=clean.lines().map(String::strip).filter(s->!s.isBlank()).findFirst().orElse("Чек");if(store.length()>80)store=store.substring(0,80);
  try{String normalized=clean.toUpperCase(Locale.ROOT).replaceAll("\\s+"," ").strip();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));return new Parsed(totals.iterator().next(),date,store,hash.substring(0,24));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
 }
}
