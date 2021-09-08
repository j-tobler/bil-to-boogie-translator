package Facts.inst;

import Facts.exp.ExpFact;

/**
 * Conditional Jump fact
 */
public class CjmpFact extends InstFact {
    public String target;
    public ExpFact condition;

    public CjmpFact(String pc, String target, ExpFact condition) {
        super(pc);
        this.target = target;
        this.condition = condition;
    }

    public String toString() {
        return String.format("%s: if (%s) goto %s;", super.pc, condition, target);
    }

}
