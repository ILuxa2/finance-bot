package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.time.*;import java.util.*;import java.nio.file.*;
public final class ReceiptFlowTest {
 static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-15T09:00:00Z"),ZoneId.of("Europe/Moscow"));static long id;
 static final String TEXT="Магазин Тест\n15.11.2026 10:30\nТовар 1 1500.00\nТовар 2 1500.00\nИТОГ 3 000,00\nНДС 500.00";
 static LegacyUserData base(){return new LegacyUserData(Money.of("10000"),OffsetDateTime.now(CLOCK).minusDays(1),List.of(),List.of(new BudgetPlan(YearMonth.of(2026,11),"Вкусняшки",Money.of("5000")),new BudgetPlan(YearMonth.of(2026,11),"Дом",Money.of("1000")),new BudgetPlan(YearMonth.of(2026,11),"Бензин",Money.of("2000"))));}
 static LocalBotEngine engine(){return new LocalBotEngine(42,base(),CLOCK).receiptReader(e->TEXT);}
 static void ok(boolean c,String why){if(!c)throw new AssertionError(why);}
 static BotReply send(LocalBotEngine e,String text){var r=e.handle(new VkEvent("message_new",99,42,42,"receipt-"+(++id),text)).orElseThrow();new VkKeyboardFactory().keyboard(r.buttons()==null?MenuCatalog.buttons(r.screen()):r.buttons());return r;}
 static BotReply click(LocalBotEngine e,BotReply r,String label){return send(e,r.buttons().stream().filter(b->b.label().equals(label)).findFirst().orElseThrow(()->new AssertionError("Missing button "+label+" in "+r.text())).command());}
 static BotReply photo(LocalBotEngine e){var reply=e.handle(new VkEvent("message_new",99,42,42,"photo-"+(++id),"",id,List.of("https://sun9-1.userapi.com/test.jpg"))).orElseThrow();new VkKeyboardFactory().keyboard(reply.buttons());return reply;}
 static BotReply allocate(LocalBotEngine e,BotReply r,String category,String amount){r=click(e,r,"Разбить по категориям");r=click(e,r,category);return send(e,amount);}
 public static void main(String[] args)throws Exception{
  var parsed=new ReceiptTextParser().parse(TEXT,LocalDate.of(2026,11,15));ok(parsed.total().equals("3000.00")&&parsed.date().equals("2026-11-15"),"Bad receipt extraction");
  for(String invalid:List.of("Магазин\nНДС 100\nКАРТА 1000", "Магазин\nИТОГ 100.00\nИТОГ 200.00", "Магазин\n31.02.2026\nИТОГ 100.00")){try{new ReceiptTextParser().parse(invalid,LocalDate.of(2026,11,15));throw new AssertionError("Guessed ambiguous receipt");}catch(IllegalArgumentException expected){}}
  var e=engine();var r=photo(e);ok(e.exportState().entries().isEmpty(),"OCR saved money");
  r=allocate(e,r,"Вкусняшки","1800");ok(r.text().contains("1 200 ₽"),"Wrong remaining amount");
  String stale=r.buttons().getFirst().command();r=allocate(e,r,"Дом","700");ok(r.text().contains("500 ₽"),"Wrong remaining after second part");
  send(e,stale);ok(e.exportState().entries().isEmpty(),"Old button committed");
  r=click(e,r,"Разбить по категориям");r=click(e,r,"Бензин");for(String bad:List.of("501","-1","0","1.001","NaN")){r=send(e,bad);ok(e.exportState().entries().isEmpty(),"Invalid allocation committed");}
  r=click(e,r,"Весь остаток");ok(r.buttons().stream().anyMatch(b->b.label().equals("✅ Сохранить чек")),"No final confirmation");
  // Correct an allocation before committing, then assign the freed amount again.
  r=click(e,r,"✏️ Распределение");r=click(e,r,"Дом");r=send(e,"600");ok(r.text().contains("100 ₽"),"Edit did not free remainder");r=allocate(e,r,"Вкусняшки","100");
  var path=Files.createTempFile("receipt-flow-",".json");try{var file=new LocalStateFile(path,42,base());file.save(e.exportState());var restored=engine();restored.restoreState(file.load().orElseThrow());e=restored;}finally{Files.delete(path);}
  String confirm=r.buttons().stream().filter(b->b.label().equals("✅ Сохранить чек")).findFirst().orElseThrow().command();send(e,confirm);
  ok(e.exportState().entries().size()==3,"Wrong receipt entry count");ok(e.exportState().entries().stream().map(t->t.entry().amount()).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add).compareTo(Money.of("3000"))==0,"Parts do not sum to total");ok(send(e,"/report").text().contains("7000.00"),"Balance not decreased once");send(e,confirm);ok(e.exportState().entries().size()==3,"Duplicate button committed");
  var committedPath=Files.createTempFile("receipt-committed-",".json");try{var stateFile=new LocalStateFile(committedPath,42,base());stateFile.save(e.exportState());var restored=engine();restored.restoreState(stateFile.load().orElseThrow());e=restored;ok(e.exportState().entries().size()==3,"Saved receipt lost after restart");}finally{Files.delete(committedPath);}
  r=photo(e);r=click(e,r,"Одной категорией");r=click(e,r,"Дом");click(e,r,"✅ Сохранить чек");ok(e.exportState().entries().size()==3,"Duplicate receipt committed");
  send(e,"/undo");ok(e.exportState().entries().isEmpty(),"Undo did not remove entire receipt");
  r=send(e,"/receipt");r=send(e,"100.50");r=click(e,r,"Одной категорией");r=click(e,r,"Дом");click(e,r,"✅ Сохранить чек");ok(e.exportState().entries().size()==1,"Manual total failed");
  r=send(e,"/receipt");r=send(e,"100");r=click(e,r,"📅 Дата");r=send(e,"2026-99-99");ok(e.exportState().entries().size()==1,"Invalid date committed");r=send(e,"2026-11-16");r=click(e,r,"Одной категорией");r=click(e,r,"Дом");r=click(e,r,"✅ Сохранить чек");ok(e.exportState().entries().size()==1&&r.text().contains("будущем"),"Future receipt committed");click(e,r,"❌ Отмена");
  // Backdated entries before opening snapshot still affect today's balance through OperationEditor.
  r=send(e,"/receipt");r=send(e,"10");r=click(e,r,"📅 Дата");r=send(e,"2026-10-01");r=click(e,r,"Одной категорией");r=click(e,r,"Дом");click(e,r,"✅ Сохранить чек");ok(send(e,"/report").text().contains("9889.50"),"Backdated receipt missing from balance");
  var failing=engine().receiptReader(event->{throw new java.io.IOException("private failure");});r=photo(failing);ok(r.text().contains("вручную")&&!r.text().contains("private"),"OCR failure not safe");r=send(failing,"1");r=click(failing,r,"Одной категорией");r=click(failing,r,"Дом");click(failing,r,"✅ Сохранить чек");ok(failing.exportState().entries().size()==1,"OCR fallback failed");
  var typed=engine();r=send(typed,"/receipt");r=send(typed,"660");r=click(typed,r,"Одной категорией");ok(r.text().contains("напишите её название"),"No text input hint");r=send(typed,"  вКуСнЯшКи  ");ok(r.text().contains("Вкусняшки · 660 ₽")&&typed.exportState().entries().isEmpty(),"Typed single category mutated finances before confirmation");click(typed,r,"✅ Сохранить чек");ok(typed.exportState().entries().size()==1,"Typed receipt not saved");send(typed,"/undo");
  r=send(typed,"/receipt");r=send(typed,"660");r=click(typed,r,"Разбить по категориям");r=send(typed,"Нет такой категории");ok(r.text().contains("не найдена")&&typed.exportState().entries().isEmpty(),"Unknown category accepted");r=send(typed,"дом");r=send(typed,"200");ok(r.text().contains("460 ₽"),"Typed split remainder wrong");r=click(typed,r,"✏️ Распределение");r=send(typed," ДОМ ");r=send(typed,"100");ok(r.text().contains("560 ₽"),"Typed allocation edit failed");
  System.out.println("Receipt flows and typed category single/split/edit/unknown-name checks passed.");
 }
}
