package Facts.Inst;

import Facts.Exp.ExpFact;
import Facts.Exp.VarFact;
import Facts.Fact;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class InitFact extends InstFact {

    private VarFact variable;

    public InitFact(VarFact variable, String label) {
        super(label);
        this.variable = variable;
    }

    public VarFact getVariable() {
        return variable;
    }

    public void setVariable(VarFact variable) {
        this.variable = variable;
    }

    @Override
    public List<ExpFact> getChildren() {
        return Collections.singletonList(variable);
    }

    @Override
    public void replace(ExpFact oldExp, ExpFact newExp) {
        if (variable.equals(oldExp)) {
            variable = (VarFact) newExp;
        }
    }

    @Override
    public List<Fact> toFactList() {
        return Arrays.asList(this, variable);
    }

    @Override
    public String toString() {
        return String.format("var %s;", variable);
    }
}
