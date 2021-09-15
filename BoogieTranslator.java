import Facts.Fact;
import Facts.exp.*;
import Facts.inst.*;
import Facts.inst.assign.AssignFact;
import Facts.inst.assign.LoadFact;
import Facts.inst.assign.MoveFact;
import Facts.inst.assign.StoreFact;
import org.antlr.v4.runtime.ParserRuleContext;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * todo:
 * [ ] implement return statements in function calls
 * [ ] implement proper modifies statements
 * [ ] unary operators like ~ probably mean 'bit flip' not 'not'. figure this out and solve
 * [ ] remove lines with references to FP when FP isn't utilised throughout the program
 * [ ] implement loops properly
 * [ ] sort out whatever this is (NF <> VF | ZF <> 0) <> 0
 *
 * [ ] write out rules for wpifrg translation to boogie
 * [ ] download boogie properly
 *
 * theoretical problems:
 * [ ] registers can store function pointers?
 * [ ] what to do if we can't just ignore FP
 * [ ] how to properly deal with SP and LR
 * [ ] dereferencing of registers when their value has been extracted from mem[]
 */

public class BoogieTranslator {

    // for writing the boogie output
    BufferedWriter writer;
    // the lines in the BIL file to translate
    List<InstFact> facts;
    // the name of the function we are currently in, or "" if we are not in a function
    String funcName = "";
    // line addresses that are used (i.e. jumped to) in the BIL program
    Set<String> usedLabels = new HashSet<>();
    // {funcName ==> [[params], [returns]]}
    Map<String, FunctionData> functionData = new HashMap<>();
    // the current index of 'lines' that is being analysed
    int lineIndex = 0;
    int nameCount = 0;

    public BoogieTranslator(List<InstFact> facts, String outputFileName) {
        try {
            writer = new BufferedWriter(new FileWriter(outputFileName, false));
        } catch (IOException e) {
            System.err.println("Error setting up file writer.");
        }
        this.facts = facts;
    }

    /**
     * Starting point for a BIL translation.
     */
    public void translate() {
        createLabels();
        createFuncParameters();
        resolveFuncParameters();
        resolveRegisters();
        printAllFacts();

        // logFunctionData();
//        for (String funcName : functionData.keySet()) {
//            System.out.println(funcName);
//            System.out.println("params: " + functionData.get(funcName).params);
//            System.out.println("return: " + functionData.get(funcName).result);
//            System.out.println("stack: " + functionData.get(funcName).stackAliases);
//        }
//        handleInit();
//        for (InstFact fact : facts) {
//            handleLine(fact);
//        }
    }

    /**
     * We want to display the labels (i.e. pc's) of all instructions whose labels are referenced elsewhere (for e.g. by
     * a jump, conditional jump or call).
     */
    private void createLabels() {
        List<String> usedLabels = new ArrayList<>();
        for (InstFact fact : facts) {
            String target = extractTargetLabel(fact);
            if (target != null) {
                usedLabels.add(target);
            }
        }
        for (InstFact fact : facts) {
            if (usedLabels.contains(fact.label.pc)) {
                fact.label.hide = false;
            }
        }
    }

    private String extractTargetLabel(InstFact fact) {
        // target labels are used in jumps, cjumps and calls
        if (fact instanceof JmpFact) {
            return ((JmpFact) fact).target;
        } else if (fact instanceof CjmpFact) {
            return ((CjmpFact) fact).target;
        } else if (fact instanceof CallFact) {
            return ((CallFact) fact).returnAddr;
        } else {
            return null;
        }
    }

    /**
     * @return list of [start, end] endpoints for function blocks.
     * 'Start' is the function header and 'end' is the function return line.
     */
    private List<Integer[]> getAllFunctions() {
        List<Integer[]> functions = new ArrayList<>();
        for (int i = 0; i < facts.size(); i++) {
            InstFact fact = facts.get(i);
            if (fact instanceof EnterSubFact) {
                Integer[] endPoints = new Integer[2];
                endPoints[0] = i;
                functions.add(endPoints);
            } else if (fact instanceof ExitSubFact) functions.get(functions.size() - 1)[1] = i;
        }
        return functions;
    }

    /**
     * We want to feed each EnterSubFact with a list of its parameters (i.e. ParamFacts) so that it can include these
     * in its procedure header, and use them for parameter resolving/dereferencing within the function body.
     */
    private void createFuncParameters() {
        for (Integer[] endpoints : getAllFunctions()) {
            int start = endpoints[0];
            int end = endpoints[1];
            EnterSubFact currentFunc = (EnterSubFact) facts.get(start);
            int i = start + 1;
            // get all explicitly listed function parameters. we assume they are all listed directly underneath
            while (i < end) {
                InstFact param = facts.get(i++);
                if (!(param instanceof ParamFact)) break;
                currentFunc.paramFacts.add((ParamFact) param);
            }
            // get all function parameters which are not explicitly listed (registers which are stored before they are assigned)
            List<VarFact> assignedRegisters = new ArrayList<>();
            while (i < end) {
                InstFact line = facts.get(i++);
                if (!(line instanceof AssignFact)) continue; // we are only concerned with assignments here
                if (line instanceof StoreFact) {
                    // e.g. mem[foo] := var;
                    StoreFact storeFact = (StoreFact) line;
                    if (!(storeFact.rhs instanceof VarFact)) continue; // assumption: parameter stores should contain a rhs which is a single variable
                    VarFact rhsVar = (VarFact) storeFact.rhs;
                    if (!isRegister(rhsVar) || assignedRegisters.contains(rhsVar)) continue; // variable is not a register, or has been assigned before
                    // this is a register that has been stored before assigned - it is a parameter
                    ParamFact param = new ParamFact("", new VarFact(generateUniqueName()), rhsVar, false);
                    param.alias = (MemFact) storeFact.lhs;
                    currentFunc.paramFacts.add(param);
                } else {
                    VarFact lhsVar = (VarFact) (line instanceof LoadFact ? ((LoadFact) line).lhs : ((MoveFact) line).lhs);
                    if (isRegister(lhsVar)) assignedRegisters.add(lhsVar);
                }
            }
            // once the parameters for a function have been created, create parameters for any call to this function too
            for (InstFact fact : facts) {
                if (!(fact instanceof CallFact)) continue;
                CallFact call = (CallFact) fact;
                if (!call.funcName.equals(currentFunc.funcName)) continue;
                for (ParamFact paramFact : currentFunc.paramFacts) {
                    if (!paramFact.is_result) call.params.add(paramFact.register);
                }
            }
        }
    }

    private boolean isRegister(VarFact varFact) {
        return varFact.name.charAt(0) == 'X';
    }

    /**
     * We want to replace register references and mem calls to the stack with a human-readable variable for the
     * parameter.
     * This also assumes that memory aliases for param variables are not accessed (loaded) before they are assigned (stored),
     * and that each parameter has no more than 1 MemFact alias.
     *
     * RELEVANT RULES
     * 1. All store instructions which contain both the mem (on the left-hand-side) and register (on the right-hand-side) assigned to a parameter are removed.
     * 2. All references to mems which are assigned to parameters are replaced with references to the human-readable name of that parameter.
     */
    private void resolveFuncParameters() {
        // implement rule 1
        for (Integer[] endpoints : getAllFunctions()) {
            int start = endpoints[0];
            int end = endpoints[1];
            EnterSubFact currentFunc = (EnterSubFact) facts.get(start);
            List<Integer> forRemoval = new ArrayList<>();
            for (int i = start; i < end; i++) {
                InstFact fact = facts.get(i);
                if (!(fact instanceof StoreFact)) continue;
                StoreFact storeFact = (StoreFact) fact;
                if (!(storeFact.rhs instanceof VarFact)) continue; // assume the rhs of the stores we're looking for consist of only a variable
                MemFact lhs = (MemFact) storeFact.lhs;
                VarFact rhs = (VarFact) storeFact.rhs;
                for (ParamFact param : currentFunc.paramFacts) {
                    if (param.alias == null) continue;
                    if (param.alias.equals(lhs) && param.register.equals(rhs)) {
                        forRemoval.add(i);
                    }
                }
            }
            forRemoval.forEach(index -> facts.remove((int) index));
        }
        // implement rule 2
        for (Integer[] endpoints : getAllFunctions()) { // we have to call this function again because we just removed some lines from the facts list
            int start = endpoints[0];
            int end = endpoints[1];
            EnterSubFact currentFunc = (EnterSubFact) facts.get(start);
            for (int i = start; i < end; i++) {
                for (ParamFact param : currentFunc.paramFacts) {
                    if (param.alias != null) replaceAllInstancesOfMem(facts.get(i), param.alias, param.name);
                }
            }
        }
    }

    private List<EnterSubFact> getAllFuncs() {
        List<EnterSubFact> funcs = new ArrayList<>();
        for (InstFact fact : facts) {
            if (fact instanceof EnterSubFact) {
                funcs.add((EnterSubFact) fact);
            }
        }
        return funcs;
    }

    // todo
    private void resolveRegisters() {
        Iterator<InstFact> iter = facts.iterator();
        while (iter.hasNext()) {
            InstFact fact = iter.next();
            // if we run into a jump, cjump or entersub (or maybe some other thing), then reset
        }
    }

    private void printAllFacts() {
        facts.forEach(System.out::print);
    }

    private void replaceAllInstancesOfVar(Fact fact, VarFact oldVar, String newName) {
        if (fact instanceof BopFact) {
            replaceAllInstancesOfVar(((BopFact) fact).e1, oldVar, newName);
            replaceAllInstancesOfVar(((BopFact) fact).e2, oldVar, newName);
        } else if (fact instanceof ExtractFact) {
            replaceAllInstancesOfVar(((ExtractFact) fact).variable, oldVar, newName);
        } else if (fact instanceof MemFact) {
            replaceAllInstancesOfVar(((MemFact) fact).exp, oldVar, newName);
        } else if (fact instanceof UopFact) {
            replaceAllInstancesOfVar(((UopFact) fact).e1, oldVar, newName);
        } else if (fact instanceof VarFact) {
            if (fact.equals(oldVar)) ((VarFact) fact).name = newName;
        } else if (fact instanceof AssignFact) {
            replaceAllInstancesOfVar(((AssignFact) fact).lhs, oldVar, newName);
            replaceAllInstancesOfVar(((AssignFact) fact).rhs, oldVar, newName);
        } else if (fact instanceof  CjmpFact) {
            replaceAllInstancesOfVar(((CjmpFact) fact).condition, oldVar, newName);
        }
    }

    private void replaceAllInstancesOfMem(Fact fact, MemFact oldMem, VarFact newVar) {
        if (fact instanceof BopFact) {
            if (((BopFact) fact).e1 instanceof MemFact && ((BopFact) fact).e1.equals(oldMem)) {
                ((BopFact) fact).e1 = newVar;
            } else {
                replaceAllInstancesOfMem(((BopFact) fact).e1, oldMem, newVar);
            }
            if (((BopFact) fact).e2 instanceof MemFact && ((BopFact) fact).e2.equals(oldMem)) {
                ((BopFact) fact).e2 = newVar;
            } else {
                replaceAllInstancesOfMem(((BopFact) fact).e2, oldMem, newVar);
            }
        } else if (fact instanceof UopFact) {
            if (((UopFact) fact).e1 instanceof MemFact && ((UopFact) fact).e1.equals(oldMem)) {
                ((UopFact) fact).e1 = newVar;
            } else {
                replaceAllInstancesOfMem(((UopFact) fact).e1, oldMem, newVar);
            }
        } else if (fact instanceof AssignFact) {
            if (((AssignFact) fact).lhs instanceof MemFact && ((AssignFact) fact).lhs.equals(oldMem)) {
                ((AssignFact) fact).lhs = newVar;
            } else {
                replaceAllInstancesOfMem(((AssignFact) fact).lhs, oldMem, newVar);
            }
            if (((AssignFact) fact).rhs instanceof MemFact && ((AssignFact) fact).rhs.equals(oldMem)) {
                ((AssignFact) fact).rhs = newVar;
            } else {
                replaceAllInstancesOfMem(((AssignFact) fact).rhs, oldMem, newVar);
            }
        }
    }

    /**
     * Searches through the code and adds all function metadata to the global functionData map.

    private void logFunctionData() {
        String funcName = "";
        FunctionData funcData = new FunctionData();
        Set<String> assignedVars = new HashSet<>();
        for (ParserRuleContext line : lines) {
            if (line.getClass() == BilParser.StmtContext.class && !funcName.equals("")) {
                // statement inside a function
                BilParser.StmtContext ctx = (BilParser.StmtContext) line;
                if (ctx.assign() != null) {
                    // assignment inside a function
                    BilParser.AssignContext assignCtx = ctx.assign();
                    // todo: check if SP is used, on the second round?

                    // find variables which are accessed before they are assigned - these are input parameters.
                    // these parameters are only accessed via store statements
                    if (assignCtx.exp().getClass().equals(BilParser.ExpStoreContext.class)) {
                        BilParser.ExpStoreContext storeCtx = (BilParser.ExpStoreContext) assignCtx.exp();
                        for (String RHSVar : RHSVars(assignCtx)) {
                            if (!assignedVars.contains(RHSVar) && RHSVar.charAt(0) == 'X') { // warning: assumes all parameter registers start with X
                                // this register is accessed by a store before it is assigned
                                String varName = generateUniqueName();
                                funcData.params.put(RHSVar, varName);
                                funcData.stackAliases.put(storeCtx.exp(1).getText(), varName);
                            }
                        }
                    }
                    assignedVars.add(LHSVar(assignCtx));
                }
            } else if (line.getClass() == BilParser.SubContext.class) {
                // enter sub
                BilParser.SubContext ctx = (BilParser.SubContext) line;
                funcName = ctx.functionName().getText();

            } else if (line.getClass() == BilParser.EndsubContext.class) {
                // exit sub
                functionData.put(funcName, funcData);
                funcName = "";
                funcData = new FunctionData();
                assignedVars = new HashSet<>();

            } else if (line.getClass() == BilParser.ParamTypesContext.class) {
                // param
                BilParser.ParamTypesContext ctx = (BilParser.ParamTypesContext) line;
                String id = ctx.param().getText();
                String variable = ctx.var().getText(); // probably some register
                if (id.contains("result")) {
                    // this is the return variable of this function
                    funcData.result.put(variable, "out");
                } else {
                    // this is an input parameter of this function
                    funcData.params.put(variable, id.replace(funcName + "_", ""));
                }
            }
        }
    }*/

    /**
     * Represents what to write at the top of the boogie file, when beginning the translation.
     * Will usually include a bunch of variable initialisation, as per usual in boogie.

    private void handleInit() {
        // registers[0] represents the value at X0
        writeToFile("const registers: [int] int;\n");
        // memory[0x00111] represents the value stored at memory address 0x00111
        // fixme: for now, only integers can be stored in memory
        writeToFile("const mem: [int] int;\n");
        writeToFile("\n");
    }

    /**
     * Handles a line of BIL.


    private void handleLine(InstFact fact) {
        // handled lines are statements, functions declarations (subs) or functions returns (end subs)
        // we don't handle ParamTypes lines
        if (fact instanceof AssignFact) {
            handleAssignment((AssignFact) fact);
        } else if (fact instanceof CallFact) {
            handleCall((CallFact) fact);
        } else if (fact instanceof CjmpFact) {
            handleCJump((CjmpFact) fact);
        } else if (fact instanceof EnterSubFact) {
            handleSub((EnterSubFact) fact);
        } else if (fact instanceof ExitSubFact) {
            handleEndSub((ExitSubFact) fact);
        } else if (fact instanceof JmpFact) {
            handleJump((JmpFact) fact);
        } else if (fact instanceof ParamFact) {
            // todo
        } else if (fact instanceof NopFact) {
            handleNop();
        } else {
            System.err.printf("Unhandled instruction: %s%n", fact);
        }
        // end each instruction with a semicolon and new line
        writeToFile(";\n");
        lineIndex++;
    }*/

    private void handleNop() {
        writeToFile("skip");
    }

    /**
     * Handles an assignment, such as a load, store or move.

    private void handleAssignment(AssignFact fact) {
        // assignments can be loads, stores or moves
        if (fact instanceof LoadFact) {
            handleLoad((LoadFact) fact);
        } else if (fact instanceof StoreFact) {
            handleStore((StoreFact) fact);
        } else if (fact instanceof MoveFact) {
            handleMove((MoveFact) fact);
        }
    }

    /**
     * Handles a load assignment.
     * fixme: Incomplete.

    private void handleLoad(LoadFact fact) {
        if (fact.rhs == null) {
            // no rhs of expression: this is likely a padding instruction
            handleNop();
        } else {
            writeToFile("");
        }

        // fixme; unsure this code will ever run
        String lhs = parseExpression(ctx.exp(1));
        String rhs = parseExpression(ctx.exp(2));
        writeToFile(String.format("%s := mem[%s]", lhs, rhs));
    }

    /**
     * Handles a store assignment.
     * @param ctx the store to handle.

    private void handleStore(BilParser.ExpStoreContext ctx) {
        // 'memwith[X0,el]:u32<-low:32[X1]'
        Integer lhs = evaluateExpression(ctx.exp(1), lineIndex);
        String lhsStr;
        if (lhs == null) {
            lhsStr = parseExpression(ctx.exp(1));
        } else {
            lhsStr = String.valueOf(lhs);
        }
        String rhs = parseExpression(ctx.exp(2)); // 'X1', potentially removes casts such as 'low:32[X1]'
        writeToFile(String.format("mem[%s] := %s", lhsStr, rhs));
    }

    /**
     * Handles a move assignment.
     * @param ctx the move to handle.

    private void handleMove(BilParser.AssignContext ctx) {
        String lhs = ctx.var().getText();
        if (lhs.charAt(0) == 'X') {
            lhs = parseRegister(lhs);
        }
        String rhs = parseExpression(ctx.exp());
        writeToFile(String.format("%s := %s", lhs, rhs));
    }

    /*
    Returns the integer value of an expression, using backtracking to dereference variables.

    How this works:
    We have an expression a + b.
    Call evaluateExpression(a + b).
    This returns evaluateExpression(a) + evaluateExpression(b).
    evaluateExpression(a) attempts to resolve a by searching backwards through the BIL code from lineNo.
    It increments back until it finds a non-statement, or an assignment statement with lhs == a.
    Once it finds this assignment statement, it returns evaluateExpression on the rhs of the statement.
    If we hit a line that isn't a statement, we fail the evaluation and return null.

    fixme: This method is incomplete: it only handles addition and subtraction of literals, and only evaluates move
    assignments. A warning is given if a non-move assignment is discovered while backtracking through the code, as this
    assignment may change the value of a variable we are trying to evaluate, resulting in an incorrect evaluation when
    skipped.

    private Integer evaluateExpression(BilParser.ExpContext ectx, int lineNo) {
        if (ectx.getClass().equals(BilParser.ExpLiteralContext.class)) {
            return Integer.decode(ectx.getText());
        } else if (ectx.getClass().equals(BilParser.ExpBopContext.class)) {
            BilParser.ExpBopContext ctx = (BilParser.ExpBopContext) ectx;
            Integer lhs = evaluateExpression(ctx.exp(0), lineNo);
            Integer rhs = evaluateExpression(ctx.exp(1), lineNo);
            if (lhs != null && rhs != null) {
                if (ctx.bop().PLUS() != null) {
                    return lhs + rhs;
                } else if (ctx.bop().MINUS() != null) {
                    return lhs - rhs;
                }
            }
        } else if (ectx.getClass().equals(BilParser.ExpVarContext.class)) {
            String var = ectx.getText();
            int lineOffset = 0;
            ParserRuleContext line;
            while ((line = lines.get(lineNo - (++lineOffset))).getClass().equals(BilParser.StmtContext.class)) {
                BilParser.StmtContext stmt = (BilParser.StmtContext) line;
                if (stmt.assign() == null) {
                    continue; // not an assign statement
                }
                BilParser.AssignContext assignment = stmt.assign();
                if (assignment.exp().getClass().equals(BilParser.ExpLoadContext.class) ||
                        assignment.exp().getClass().equals(BilParser.ExpStoreContext.class)) {
                    // we don't evaluate non-move assignments yet. todo
                    System.err.printf("Can't guarantee accuracy of variable substitution for %s as a non-move " +
                            "assignment was encountered%n", var);
                    continue;
                }
                if (!assignment.var().getText().equals(var)) {
                    continue; // this assign statement doesn't update the variable we're evaluating
                }
                return evaluateExpression(assignment.exp(), lineNo - lineOffset);
            } // reached a non-statement
        }
        System.err.printf("Failed to evaluate expression: %s%n", ectx.getText());
        return null;
    }

    /**
     * Handles a jump statement.
     * @param ctx the jump to handle.
     */
    private void handleJump(BilParser.JmpContext ctx) {
        String target = "";
        if (ctx.var() != null) {
            target = ctx.var().getText();
        } else if (ctx.addr() != null) {
            target = ctx.addr().getText();
        }
        writeToFile(String.format("goto label%s", target));
    }

    /**
     * Handles a conditional-jump statement.
     * @param ctx the conditional-jump to handle.
     */
    private void handleCJump(BilParser.CjmpContext ctx) {
        String cond = ctx.var().getText();
        String target = ctx.addr().getText();
        writeToFile(String.format("if (%s) {goto label%s}", cond, target));
    }

    /**
     * Handles a call statement.
     * @param ctx the call to handle.
     */
    private void handleCall(BilParser.CallContext ctx) {
        // fixme: bill parser is having trouble understanding when we reach the end of a function call, and sometimes throws a null pointer exception if it doesn't think the end was reached
        try {
            writeToFile(String.format("call %s(); goto label%s", ctx.functionName().getText(), ctx.returnaddr().addr().getText()));
        } catch (NullPointerException e) {
            System.err.println("ignored null pointer exception");
        }
    }

    /**
     * Handles a sub (i.e. function/procedure) line.
     * @param ctx the sub to handle.
     */
    private void handleSub(BilParser.SubContext ctx) {
        String functionName = ctx.functionName().getText();
        List<BilParser.ParamContext> params = ctx.param();
        // at this point, we just assume that the registers and memory are modified by every function
        // this can be cleaned up on a second parsing
        writeToFile(String.format("procedure %s()%n    modifies mem%n    modifies registers", functionName));
        funcName = functionName;
    }

    /**
     * Handles an end-sub line.
     * E.g. "call LR with noreturn".
     * @param ctx the end-sub to handle.
     */
    private void handleEndSub(BilParser.EndsubContext ctx) {
        writeToFile(String.format("}%n"));
        funcName = "";
    }

    /**
     * Translates the given expression into boogie.
     * Currently works for binary operations, unary operations, variables, literals and extraction expressions.
     *expression to translate.
     * @return the boogie translation of the expression.

    private String parseExpression(BilParser.ExpContext ctx) {
        // expressions can be binary operations, unary operations, variables, literals or extractions (i.e. bit slices)
        ctx = ignoreUnhandledContexts(ctx);
        if (ctx.getClass().equals(BilParser.ExpBopContext.class)) {
            return parseBinaryOperation((BilParser.ExpBopContext) ctx);
        } else if (ctx.getClass().equals(BilParser.ExpUopContext.class)) {
            return parseUnaryOperation((BilParser.ExpUopContext) ctx);
        } else if (ctx.getClass().equals(BilParser.ExpVarContext.class)) {
            return parseVariableExpression((BilParser.ExpVarContext) ctx);
        } else if (ctx.getClass().equals(BilParser.ExpLiteralContext.class)) {
            return parseLiteralExpression((BilParser.ExpLiteralContext) ctx);
        } else if (ctx.getClass().equals(BilParser.ExpExtractContext.class)) {
            return parseExtractionExpression((BilParser.ExpExtractContext) ctx);
        }
        System.err.println("Unhandled expression detected: " + ctx.getText());
        return "";// expressions can be binary operations, unary operations, variables, literals or extractions (i.e. bit slices)
        ctx = ignoreUnhandledContexts(ctx);
        if (ctx.getClass().equals(BilParser.ExpBopContext.class)) {
            return parseBinaryOperation((BilParser.ExpBopContext) ctx);
        } else if (ctx.getClass().equals(BilParser.ExpUopContext.class)) {
            return parseUnaryOperation((BilParser.ExpUopContext) ctx);
        } else if (ctx.getClass().equals(BilParser.ExpVarContext.class)) {
            return parseVariableExpression((BilParser.ExpVarContext) ctx);
        } else if (ctx.getClass().equals(BilParser.ExpLiteralContext.class)) {
            return parseLiteralExpression((BilParser.ExpLiteralContext) ctx);
        } else if (ctx.getClass().equals(BilParser.ExpExtractContext.class)) {
            return parseExtractionExpression((BilParser.ExpExtractContext) ctx);
        }
        System.err.println("Unhandled expression detected: " + ctx.getText());
        return "";
    }

    private String parseBinaryOperation(BilParser.ExpBopContext ctx) {
        String left = parseExpression(ctx.exp(0));
        String right = parseExpression(ctx.exp(1));
        String op = ctx.bop().getText();
        if (op.equals("=")) {
            op = "=="; // a little patching here, a little there...
        }
        return String.format("(%s) %s (%s)", left, op, right);
    }

    private String parseUnaryOperation(BilParser.ExpUopContext ctx) {
        String exp = parseExpression(ctx.exp());
        String op = ctx.uop().getText();
        return String.format("%s (%s)", op, exp);
    }

    private String parseVariableExpression(BilParser.ExpVarContext ctx) {
        // vars can be flags, registers (X[num]) or "expression bundles" (#[num]) where [num] is some integer
        String variable = ctx.var().getText();
        if (variable.charAt(0) == 'X') {
            // variable is a register, replace with call to register map
            return parseRegister(variable);
        } else if (variable.charAt(0) == '#') {
            // variable is an expression bundle todo
            return variable;
        } else {
            return variable;
        }
    }

    private String parseLiteralExpression(BilParser.ExpLiteralContext ctx) {
        return ctx.literal().getText();
    }

    private String parseExtractionExpression(BilParser.ExpExtractContext ctx) {
        int firstNat = Integer.parseInt(ctx.nat(0).getText());
        int secondNat = Integer.parseInt(ctx.nat(1).getText());
        String exp = parseExpression(ctx.exp());
        // fixme: big warning! this is broken! assumes all bit vectors are 64 bits long
        // wasn't sure how to get the length of a bit vector: might have to get it from somewhere else in the program and keep track of a variable
        return String.format("%s[%d:%d]", exp, 64-firstNat, 64-secondNat-1); // fixme: in future, we want to properly translate exp before jamming it in here
    }

    private String LHSVar(BilParser.AssignContext ctx) {
        if (ctx.exp().getClass().equals(BilParser.ExpLoadContext.class)) {
            BilParser.ExpLoadContext ctxLoad = (BilParser.ExpLoadContext) ctx.exp();
            return ctxLoad.exp(1).getText();
        } else if (ctx.exp().getClass().equals(BilParser.ExpStoreContext.class)) {
            BilParser.ExpStoreContext ctxStore = (BilParser.ExpStoreContext) ctx.exp();
            return ctxStore.exp(1).getText();
        } else {
            return ctx.var().getText();
        }
    }

    private List<String> RHSVars(BilParser.AssignContext ctx) {
        if (ctx.exp().getClass().equals(BilParser.ExpLoadContext.class)) {
            BilParser.ExpLoadContext ctxLoad = (BilParser.ExpLoadContext) ctx.exp();
            if (ctxLoad.exp(2) == null) {
                return new ArrayList<>(); // RHS of loads (such as pads) may be null
            }
            return extractAllVars(ctxLoad.exp(2));
        } else if (ctx.exp().getClass().equals(BilParser.ExpStoreContext.class)) {
            BilParser.ExpStoreContext ctxStore = (BilParser.ExpStoreContext) ctx.exp();
            return extractAllVars(ctxStore.exp(2));
        } else {
            return extractAllVars(ctx.exp());
        }
    }

    private List<String> extractAllVars(BilParser.ExpContext ctx) {
        // expressions can be binary operations, unary operations, variables, literals or extractions (i.e. bit slices)
        ctx = ignoreUnhandledContexts(ctx);
        List<String> result = new ArrayList<>();
        if (ctx.getClass().equals(BilParser.ExpBopContext.class)) {
            BilParser.ExpBopContext _ctx = (BilParser.ExpBopContext) ctx;
            result.addAll(extractAllVars(_ctx.exp(0)));
            result.addAll(extractAllVars(_ctx.exp(1)));
        } else if (ctx.getClass().equals(BilParser.ExpUopContext.class)) {
            BilParser.ExpUopContext _ctx = (BilParser.ExpUopContext) ctx;
            result.addAll(extractAllVars(_ctx.exp()));
        } else if (ctx.getClass().equals(BilParser.ExpVarContext.class)) {
            BilParser.ExpVarContext _ctx = (BilParser.ExpVarContext) ctx;
            result.add(_ctx.var().getText());
        } else if (ctx.getClass().equals(BilParser.ExpExtractContext.class)) {
            BilParser.ExpExtractContext _ctx = (BilParser.ExpExtractContext) ctx;
            result.addAll(extractAllVars(_ctx.exp()));
        } else {
            System.err.println("Unhandled expression detected: " + ctx.getText());
        }
        return result;
    }

    /**
     * Skips all unhandled expression contexts.
     *
     * Unhandled expressions are:
     * - Casts
     *
     * @param ectx an expression context
     * @return expression context that is not unhandled
     */
    private BilParser.ExpContext ignoreUnhandledContexts(BilParser.ExpContext ectx) {
        if (ectx.getClass().equals(BilParser.ExpCastContext.class)) {
            /* Ignore all casts */
            ectx = ((BilParser.ExpCastContext) ectx).exp();
            return ignoreUnhandledContexts(ectx);
        } else {
            return ectx;
        }
    }

    private String parseRegister(String registerID) {
        return String.format("registers[%d]", Integer.parseInt(registerID.substring(1)));
    }

    private void writeToFile(String text) {
        try {
            writer.write(text);
            writer.flush();
        } catch (IOException e) {
            System.err.println("Error writing to file.");
        }
    }

    private class FunctionData {
        // map from register id to the human-readable parameter variable
        Map<String, String> params = new HashMap<>();
        // map from register id to the human-readable output variable
        Map<String, String> result = new HashMap<>();
        // map from store/load LHSs to the human-readable parameter variable
        Map<String, String> stackAliases = new HashMap<>();
        // true iff SP cannot be removed from the function due to its redundancy (i.e. it has an important use in the function)
        boolean spUsed = false;
        // constructor
        FunctionData() {}
    }

    private String generateUniqueName() {
        return "p" + nameCount++;
    }
}


