package financeos.vk;
import financeos.core.*;import java.math.*;import java.time.*;import java.util.*;
/** Serializable UI draft. No financial writes or network calls occur here. */
public final class ReceiptFlow {
 public record Part(String category,String amount){}
 public record Draft(String nonce,String total,String date,String store,String fingerprint,List<Part> parts,String phase,String selected,boolean single,boolean editing,int page){public Draft{parts=List.copyOf(parts);}}
 public record Result(Draft draft,String text,List<MenuButton> buttons,boolean commit,boolean cancelled){}
 static String nonce(){return UUID.randomUUID().toString();}
 public static Draft blank(LocalDate today){return new Draft(nonce(),"0",today.toString(),"Чек",nonce().replace("-",""),List.of(),"INPUT","",false,false,0);}
 public static Draft parsed(ReceiptTextParser.Parsed p){return new Draft(nonce(),p.total(),p.date(),p.store(),p.fingerprint(),List.of(),"REVIEW","",false,false,0);}
 public static boolean totalKnown(Draft d){return Money.of(d.total()).signum()>0;}
 public static BigDecimal remaining(Draft d){return Money.of(d.total()).subtract(d.parts().stream().map(p->Money.of(p.amount())).reduce(BigDecimal.ZERO,BigDecimal::add));}
 static Draft change(Draft d,String phase,String selected,boolean single,boolean editing,int page){return new Draft(nonce(),d.total(),d.date(),d.store(),d.fingerprint(),d.parts(),phase,selected,single,editing,page);}
 static String label(String s){return s.codePointCount(0,s.length())<=40?s:s.substring(0,s.offsetByCodePoints(0,39))+"…";}
 static String key(String s){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,12);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
 static MenuButton button(Draft d,String label,String action){return new MenuButton(label,"/receipt/"+d.nonce()+"/"+action);}
 public static Result step(Draft d,String input,List<String> categories){
  try{
   if(input.startsWith("/receipt/")){
    var f=input.split("/",-1);if(f.length!=4||!f[2].equals(d.nonce()))return render(d,categories,"Эта кнопка устарела. Используйте последние кнопки чека.");String action=f[3];
    if(!totalKnown(d)&&!action.equals("cancel")&&!action.equals("total"))return render(change(d,"INPUT","",false,false,0),categories,"Сначала введите итоговую сумму чека, например 660.");
    if(action.equals("cancel"))return new Result(d,"Ввод чека отменён. Деньги не списаны.",List.of(new MenuButton("🏠 Меню","/start")),false,true);
    if(action.equals("review"))return render(change(d,"REVIEW","",false,false,0),categories,"");
    if(action.equals("total"))return render(change(d,"TOTAL","",false,false,0),categories,"Введите полную сумму чека. Дробную часть пишите через точку.");
    if(action.equals("date"))return render(change(d,"DATE","",false,false,0),categories,"Введите дату чека: ГГГГ-ММ-ДД.");
    if(action.equals("store"))return render(change(d,"STORE","",false,false,0),categories,"Введите магазин или комментарий, до 80 символов.");
    if(action.equals("one")||action.equals("split"))return render(new Draft(nonce(),d.total(),d.date(),d.store(),d.fingerprint(),action.equals("one")?List.of():d.parts(),"CATEGORY","",action.equals("one"),false,0),categories,"");
    if(action.equals("edit"))return render(change(d,"EDIT_CATEGORY","",false,true,0),categories,"");
    if(action.equals("next")||action.equals("prev"))return render(change(d,d.phase(),d.selected(),d.single(),d.editing(),Math.max(0,d.page()+(action.equals("next")?1:-1))),categories,"");
    if(action.startsWith("cat-") && (d.phase().equals("CATEGORY")||d.phase().equals("EDIT_CATEGORY"))){
     var f2=action.split("-");int i=Integer.parseInt(f2[1]);var options=options(d,categories);String selected=options.get(i);if(f2.length!=3||!key(selected).equals(f2[2])||(!d.editing()&&!categories.contains(selected)))return render(d,categories,"Категории изменились. Выберите заново.");
     return selectCategory(d,selected,categories);
    }
    if(action.equals("all")&&d.phase().equals("AMOUNT")){var available=remaining(d).add(d.editing()?existing(d,d.selected()):BigDecimal.ZERO);return allocate(d,d.selected(),available,d.editing(),categories);}
    if(action.equals("save")&&d.phase().equals("REVIEW")&&remaining(d).signum()==0&&!d.parts().isEmpty()){
     if(d.parts().stream().anyMatch(p->!categories.contains(p.category())))return render(d,categories,"Категории изменились. Исправьте распределение перед сохранением.");
     return new Result(d,"Чек готов к сохранению.",List.of(),true,false);
    }
    return render(d,categories,"Выберите действие на текущем шаге.");
   }
   switch(d.phase()){
    case "CATEGORY","EDIT_CATEGORY"->{var matches=options(d,categories).stream().filter(c->CategorySections.key(c).equals(CategorySections.key(input))).toList();if(matches.size()!=1)return render(d,categories,matches.isEmpty()?"Категория не найдена. Введите существующее название или выберите кнопкой.":"Найдено несколько категорий. Уточните выбор кнопкой.");return selectCategory(d,matches.getFirst(),categories);}
    case "INPUT","TOTAL","REVIEW"->{var amount=positive(input);if(amount.compareTo(Money.of(d.total()).subtract(remaining(d)))<0)throw new IllegalArgumentException();return render(new Draft(nonce(),amount.toPlainString(),d.date(),d.store(),d.fingerprint(),d.parts(),"REVIEW","",false,false,0),categories,"");}
    case "DATE"->{var date=LocalDate.parse(input);if(date.getYear()<2000||date.getYear()>2100)throw new IllegalArgumentException();return render(new Draft(nonce(),d.total(),date.toString(),d.store(),d.fingerprint(),d.parts(),"REVIEW","",false,false,0),categories,"");}
    case "STORE"->{if(input.isBlank()||input.length()>80)throw new IllegalArgumentException();return render(new Draft(nonce(),d.total(),d.date(),input,d.fingerprint(),d.parts(),"REVIEW","",false,false,0),categories,"");}
    case "AMOUNT"->{var amount=Money.of(input);if(amount.signum()<0||(!d.editing()&&amount.signum()==0))throw new IllegalArgumentException();return allocate(d,d.selected(),amount,d.editing(),categories);}
    default->{return render(d,categories,"Выберите действие кнопкой.");}
   }
  }catch(IllegalArgumentException|IndexOutOfBoundsException|DateTimeException invalid){return render(d,categories,"Проверьте ввод: сумма через точку, не больше остатка; дата ГГГГ-ММ-ДД. Для удаления распределения в режиме изменения введите 0.");}
 }
 static Result selectCategory(Draft d,String selected,List<String> categories){if(d.single())return allocate(d,selected,Money.of(d.total()),false,categories);return render(change(d,"AMOUNT",selected,false,d.editing(),0),categories,"");}
 static BigDecimal positive(String text){var amount=Money.of(text);if(amount.signum()<=0||amount.precision()>14)throw new IllegalArgumentException();return amount;}
 static BigDecimal existing(Draft d,String category){return d.parts().stream().filter(p->p.category().equals(category)).map(p->Money.of(p.amount())).reduce(BigDecimal.ZERO,BigDecimal::add);}
 static Result allocate(Draft d,String category,BigDecimal amount,boolean replace,List<String> categories){
  if((!categories.contains(category)&&!(replace&&amount.signum()==0))||amount.precision()>14)throw new IllegalArgumentException();BigDecimal max=remaining(d).add(replace?existing(d,category):BigDecimal.ZERO);if(amount.compareTo(max)>0)throw new IllegalArgumentException();
  var parts=new ArrayList<>(d.parts());var previous=existing(d,category);parts.removeIf(p->p.category().equals(category));var next=replace?amount:previous.add(amount);if(next.signum()>0)parts.add(new Part(category,next.toPlainString()));if(parts.size()>40)throw new IllegalArgumentException();
  return render(new Draft(nonce(),d.total(),d.date(),d.store(),d.fingerprint(),parts,"REVIEW","",false,false,0),categories,"");
 }
 static List<String> options(Draft d,List<String> categories){return d.phase().equals("EDIT_CATEGORY")?d.parts().stream().map(Part::category).toList():categories;}
 public static Result render(Draft d,List<String> categories,String note){
  var text=new StringBuilder(note.isBlank()?"":note+"\n\n");text.append(totalKnown(d)?"🧾 Чек · "+PlanFactView.money(Money.of(d.total())):"🧾 Чек · сумма не определена").append("\n📅 ").append(d.date()).append(" · ").append(d.store());
  for(var p:d.parts())text.append("\n").append(p.category()).append(" · ").append(PlanFactView.money(Money.of(p.amount())));
  if(totalKnown(d))text.append("\n\nОсталось распределить · ").append(PlanFactView.money(remaining(d)));
  else text.append("\n\nВведите итог чека вручную, например 660. Это не нулевая сумма.");
  text.append("\nДанные пока не сохранены.");
  var buttons=new ArrayList<MenuButton>();
  if(d.phase().equals("INPUT"))text.append("\nПришлите одно фото чека, текст чека или введите его итоговую сумму.");
  else if(d.phase().equals("CATEGORY")||d.phase().equals("EDIT_CATEGORY")){
   var options=options(d,categories);int pages=Math.max(1,(options.size()+2)/3),page=Math.min(d.page(),pages-1);text.append("\n\nВыберите категорию кнопкой или напишите её название.\nСтраница · ").append(page+1).append(" / ").append(pages);
   for(int i=page*3;i<Math.min(options.size(),page*3+3);i++){String cat=options.get(i);text.append("\n").append(cat);buttons.add(button(d,label(cat),"cat-"+i+"-"+key(cat)));}
   if(page>0)buttons.add(button(d,"⬅️ Ранее","prev"));if(page+1<pages)buttons.add(button(d,"➡️ Далее","next"));if(options.isEmpty())text.append("\nКатегорий нет. Создайте их через «Ещё» → «Разделы».");
  }else if(d.phase().equals("AMOUNT")){text.append("\n\nКатегория · ").append(d.selected()).append("\nВведите сумму для категории. Дробная часть через точку.");if(d.editing())text.append("\nСейчас · ").append(PlanFactView.money(existing(d,d.selected()))).append(". Введите новую сумму или 0 для удаления.");buttons.add(button(d,"Весь остаток","all"));}
  else if(d.phase().equals("REVIEW")){
   text.append("\nПроверьте сумму, дату и магазин перед сохранением. Чтобы исправить итог, можно просто отправить сумму сообщением.");
   if(remaining(d).signum()==0&&!d.parts().isEmpty())buttons.add(button(d,"✅ Сохранить чек","save"));
   else {if(d.parts().isEmpty())buttons.add(button(d,"Одной категорией","one"));buttons.add(button(d,"Разбить по категориям","split"));}
   if(!d.parts().isEmpty())buttons.add(button(d,"✏️ Распределение","edit"));buttons.add(button(d,"💰 Сумма","total"));buttons.add(button(d,"📅 Дата","date"));buttons.add(button(d,"Магазин","store"));
  }
  if(!d.phase().equals("REVIEW")&&!d.phase().equals("INPUT"))buttons.add(button(d,"⬅️ Назад","review"));
  buttons.add(button(d,"❌ Отмена","cancel"));buttons.add(new MenuButton("🏠 Меню","/start"));
  return new Result(d,text.toString(),List.copyOf(buttons),false,false);
 }
}
