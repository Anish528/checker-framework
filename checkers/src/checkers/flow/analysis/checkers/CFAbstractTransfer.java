package checkers.flow.analysis.checkers;

import java.util.List;

import checkers.flow.analysis.ConditionalTransferResult;
import checkers.flow.analysis.FlowExpressions;
import checkers.flow.analysis.FlowExpressions.Receiver;
import checkers.flow.analysis.RegularTransferResult;
import checkers.flow.analysis.TransferFunction;
import checkers.flow.analysis.TransferInput;
import checkers.flow.analysis.TransferResult;
import checkers.flow.cfg.node.AbstractNodeVisitor;
import checkers.flow.cfg.node.AssertNode;
import checkers.flow.cfg.node.AssignmentNode;
import checkers.flow.cfg.node.CaseNode;
import checkers.flow.cfg.node.EqualToNode;
import checkers.flow.cfg.node.FieldAccessNode;
import checkers.flow.cfg.node.LocalVariableNode;
import checkers.flow.cfg.node.MethodInvocationNode;
import checkers.flow.cfg.node.Node;
import checkers.flow.cfg.node.NotEqualNode;
import checkers.types.AnnotatedTypeFactory;
import checkers.types.AnnotatedTypeMirror;
import checkers.util.TreeUtils;

import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;

/**
 * The default analysis transfer function for the Checker Framework propagates
 * information through assignments and uses the {@link AnnotatedTypeFactory} to
 * provide checker-specific logic how to combine types (e.g., what is the type
 * of a string concatenation, given the types of the two operands) and as an
 * abstraction function (e.g., determine the annotations on literals)..
 * 
 * @author Charlie Garrett
 * @author Stefan Heule
 */
public abstract class CFAbstractTransfer<V extends CFAbstractValue<V>, S extends CFAbstractStore<V, S>, T extends CFAbstractTransfer<V, S, T>>
        extends AbstractNodeVisitor<TransferResult<V, S>, TransferInput<V, S>>
        implements TransferFunction<V, S> {

    /**
     * The analysis class this store belongs to.
     */
    protected CFAbstractAnalysis<V, S, T> analysis;

    public CFAbstractTransfer(CFAbstractAnalysis<V, S, T> analysis) {
        this.analysis = analysis;
    }

    /**
     * @return The abstract value of a non-leaf tree {@code tree}, as computed
     *         by the {@link AnnotatedTypeFactory}.
     */
    protected V getValueFromFactory(Tree tree) {
        analysis.setCurrentTree(tree);
        AnnotatedTypeMirror at = analysis.factory.getAnnotatedType(tree);
        analysis.setCurrentTree(null);
        return analysis.createAbstractValue(at.getAnnotations());
    }

    /**
     * The initial store maps method formal parameters to their currently most
     * refined type.
     */
    @Override
    public S initialStore(MethodTree tree, List<LocalVariableNode> parameters) {
        S info = analysis.createEmptyStore();

        for (LocalVariableNode p : parameters) {
            AnnotatedTypeMirror anno = analysis.getFactory().getAnnotatedType(
                    p.getElement());
            info.initializeMethodParameter(p,
                    analysis.createAbstractValue(anno.getAnnotations()));
        }

        return info;
    }

    /**
     * The default visitor returns the input information unchanged, or in the
     * case of conditional input information, merged.
     */
    @Override
    public TransferResult<V, S> visitNode(Node n, TransferInput<V, S> in) {
        // TODO: Perform type propagation separately with a thenStore and an
        // elseStore.

        S info = in.getRegularStore();
        V value = null;

        // TODO: handle implicit/explicit this and go to correct factory method
        Tree tree = n.getTree();
        if (tree != null) {
            if (TreeUtils.canHaveTypeAnnotation(tree)) {
                value = getValueFromFactory(tree);
            }
        }

        return new RegularTransferResult<>(value, info);
    }

    @Override
    public TransferResult<V, S> visitFieldAccess(FieldAccessNode n,
            TransferInput<V, S> p) {
        S store = p.getRegularStore();
        V value = store.getValue(n);
        return new RegularTransferResult<>(value, store);
    }

    /**
     * Use the most specific type information available according to the store.
     */
    @Override
    public TransferResult<V, S> visitLocalVariable(LocalVariableNode n,
            TransferInput<V, S> in) {
        S store = in.getRegularStore();
        V value = store.getValue(n);
        return new RegularTransferResult<>(value, store);
    }

    @Override
    public TransferResult<V, S> visitEqualTo(EqualToNode n,
            TransferInput<V, S> p) {
        TransferResult<V, S> res = super.visitEqualTo(n, p);

        Node leftN = n.getLeftOperand();
        Node rightN = n.getRightOperand();
        V leftV = p.getValueOfSubNode(leftN);
        V rightV = p.getValueOfSubNode(rightN);

        // if annotations differ, use the one that is more precise for both
        // sides (and add it to the store if possible)
        ConditionalTransferResult<V, S> a;
        a = strengthenAnnotationOfEqualTo(res, rightN, leftV, rightV, false);
        if (a != null) {
            return a;
        }
        a = strengthenAnnotationOfEqualTo(res, leftN, rightV, leftV, false);
        if (a != null) {
            return a;
        }

        return res;
    }
    
    @Override
    public TransferResult<V, S> visitNotEqual(NotEqualNode n,
            TransferInput<V, S> p) {
        TransferResult<V, S> res = super.visitNotEqual(n, p);

        Node leftN = n.getLeftOperand();
        Node rightN = n.getRightOperand();
        V leftV = p.getValueOfSubNode(leftN);
        V rightV = p.getValueOfSubNode(rightN);

        // if annotations differ, use the one that is more precise for both
        // sides (and add it to the store if possible)
        ConditionalTransferResult<V, S> a;
        a = strengthenAnnotationOfEqualTo(res, rightN, leftV, rightV, true);
        if (a != null) {
            return a;
        }
        a = strengthenAnnotationOfEqualTo(res, leftN, rightV, leftV, true);
        if (a != null) {
            return a;
        }

        return res;
    }

    /**
     * Refine the annotation of {@code secondNode} if the annotation
     * {@code secondValue} is less precise than {@code firstvalue}. This is
     * possible, if {@code secondNode} is an expression that is tracked by the
     * store (e.g., a local variable or a field).
     * 
     * @param res
     *            The previous result.
     * @param notEqualTo
     *            If true, indicates that the logic is flipped (i.e., the
     *            information is added to the {@code elseStore} instead of the
     *            {@code thenStore}) for a not-equal comparison.
     * @return The conditional transfer result (if information has been added),
     *         or {@code null}.
     */
    protected ConditionalTransferResult<V, S> strengthenAnnotationOfEqualTo(
            TransferResult<V, S> res, Node secondNode, V firstValue,
            V secondValue, boolean notEqualTo) {
        if (firstValue != null
                && (secondValue == null || firstValue.isSubtypeOf(secondValue))) {
            Receiver secondInternal = FlowExpressions
                    .internalReprOf(secondNode);
            if (!secondInternal.containsUnknown()) {
                S thenStore = res.getRegularStore();
                S elseStore = thenStore.copy();
                if (notEqualTo) {
                    elseStore.insertValue(secondInternal, firstValue);
                } else {
                    thenStore.insertValue(secondInternal, firstValue);
                }
                return new ConditionalTransferResult<>(res.getResultValue(),
                        thenStore, elseStore);
            }
        }
        return null;
    }

    /**
     * Determine abstract value of right-hand side and update the store
     * accordingly to the assignment.
     */
    @Override
    public TransferResult<V, S> visitAssignment(AssignmentNode n,
            TransferInput<V, S> in) {
        Node lhs = n.getTarget();
        Node rhs = n.getExpression();

        S info = in.getRegularStore();
        V rhsValue = in.getValueOfSubNode(rhs);

        // assignment to a local variable
        if (lhs instanceof LocalVariableNode) {
            LocalVariableNode var = (LocalVariableNode) lhs;
            info.updateForAssignment(var, rhsValue);
        }

        // assignment to a field
        else if (lhs instanceof FieldAccessNode) {
            FieldAccessNode fieldAccess = (FieldAccessNode) lhs;
            info.updateForAssignment(fieldAccess, rhsValue);
        }

        else {
            info.updateForUnknownAssignment(lhs);
        }

        // TODO: other assignments

        return new RegularTransferResult<>(rhsValue, info);
    }

    @Override
    public TransferResult<V, S> visitMethodInvocation(MethodInvocationNode n,
            TransferInput<V, S> in) {

        S info = in.getRegularStore();

        // use value from factory (no flowsensitive information available)
        V resValue = getValueFromFactory(n.getTree());

        info.updateForMethodCall(n);

        return new RegularTransferResult<>(resValue, info);
    }

    /**
     * An assert produces no value and since it may be disabled, it has no
     * effect on the store.
     */
    @Override
    public TransferResult<V, S> visitAssert(AssertNode n, TransferInput<V, S> in) {
        // TODO: Perform type propagation separately with a thenStore and an
        // elseStore.
        return new RegularTransferResult<>(null, in.getRegularStore());
    }

    /**
     * A case produces no value, but it may imply some facts about the argument
     * to the switch statement.
     */
    @Override
    public TransferResult<V, S> visitCase(CaseNode n, TransferInput<V, S> in) {
        return new RegularTransferResult<>(null, in.getRegularStore());
    }
}