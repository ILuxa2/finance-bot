package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.time.*;import java.util.*;
public final class ReceiptTotalRegressionTest {
 static long id;static final Clock C=Clock.fixed(Instant.parse("2026-11-15T09:00:00Z"),ZoneId.of("Europe/Moscow"));
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static BotReply send(LocalBotEngine e,String t){return e.handle(new VkEvent("message_new",99,42,42,"total-"+(++id),t)).orElseThrow();}
 static LocalBotEngine fresh(){return new LocalBotEngine(42,new LegacyUserData(Money.of("10000"),OffsetDateTime.now(C).minusDays(1),List.of(),List.of()),C);}
 public static void main(String[] args){
  var parser=new ReceiptTextParser();
  var valid=List.of("ИТОГ 660", "ИТОГ: 660.00", "ИТОГО: = 660,00 РУБ.","ИТОГ =\n660,00", "ИТОГ\n=\n660.00", "К ОПЛАТЕ 660,0", "СУММА РАСЧЁТА 660.00", "СУММА ПО ЧЕКУ: 660.00", "ВСЕГО К ОПЛАТЕ 660", "TOTAL DUE 660.00", "ИТОГ РУБ. 660,00");
  for(String line:valid){try{check(parser.parse("Магазин\n15.11.2026\n"+line,LocalDate.now(C)).total().equals("660.00"),"Failed format: "+line);}catch(IllegalArgumentException bad){throw new AssertionError("Failed format: "+line,bad);}}
  check(parser.parse("Магазин\nИТОГ 1 660,00",LocalDate.now(C)).total().equals("1660.00"),"Thousands separator lost");
  for(String text:List.of("ИТОГ 0.00", "ИТОГ -660.00", "ИТОГ = -660.00", "НДС 660.00\nБЕЗНАЛИЧНЫМИ 660.00", "ИТОГ\nНДС 660.00", "ИТОГ 660.00\nК ОПЛАТЕ 700.00", "ИТОГ 660 00")){
   try{parser.parse(text,LocalDate.now(C));throw new AssertionError("Guessed invalid total: "+text);}catch(IllegalArgumentException expected){}
  }
  var e=fresh().receiptReader(event->"Магазин\nНДС 660.00");
  var r=e.handle(new VkEvent("message_new",99,42,42,"missing-total","",1,List.of("https://sun9-1.userapi.com/a.jpg"))).orElseThrow();
  check(r.text().contains("сумма не определена")&&!r.text().contains("0 ₽")&&!r.text().contains("Осталось распределить"),"Unknown total displayed as zero");
  r=send(e,"660");check(r.text().contains("660 ₽")&&r.buttons().stream().anyMatch(b->b.label().equals("Разбить по категориям")),"Manual 660 after photo not accepted");
  String stale=r.buttons().getFirst().command();r=send(e,"660.50");check(r.text().contains("660.50 ₽"),"Direct correction after photo not accepted");send(e,stale);check(e.exportState().entries().isEmpty(),"Old receipt button committed");
  send(e,"/receipt");r=send(e,"ИТОГ: = 660,00 РУБ.");check(r.text().contains("660 ₽"),"Single-line text after photo not recognized");
  send(e,"/receipt");r=send(e,"0");check(!r.text().contains("0 ₽"),"Invalid manual zero displayed as total");
  var blank=ReceiptFlow.blank(LocalDate.now(C));var back=ReceiptFlow.step(blank,"/receipt/"+blank.nonce()+"/review",List.of("Еда"));check(back.draft().phase().equals("INPUT")&&back.buttons().stream().noneMatch(b->b.label().equals("Одной категорией")),"Zero draft entered allocation");
  check(e.exportState().entries().isEmpty(),"Total correction saved finance without confirmation");
  System.out.println("Receipt total regression: 660 formats, OCR line breaks, no zero placeholder, manual/text correction, stale and negative-total guards passed.");
 }
}
