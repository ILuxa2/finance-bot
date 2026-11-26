package financeos.core;
import java.math.BigDecimal;
import java.time.YearMonth;
public record PlanEdit(YearMonth month, String source, String category, BigDecimal amount, boolean canonical, boolean deleted) {
    public PlanEdit(YearMonth month,String source,String category,BigDecimal amount,boolean canonical){this(month,source,category,amount,canonical,false);}
    public PlanEdit(YearMonth month,String source,String category,BigDecimal amount){this(month,source,category,amount,false);}
    public PlanEdit {
        if(month==null||source==null||source.isBlank()||source.length()>80||category==null||category.isBlank()||category.length()>80||amount==null||amount.signum()<0)throw new IllegalArgumentException("Invalid plan edit");
        source=source.strip().replaceAll("\\s+"," ");category=category.strip().replaceAll("\\s+"," ");
    }
}
