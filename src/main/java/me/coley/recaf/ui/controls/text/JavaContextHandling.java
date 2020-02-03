package me.coley.recaf.ui.controls.text;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.resolution.Resolvable;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import javafx.application.Platform;
import me.coley.recaf.control.gui.GuiController;
import me.coley.recaf.parse.source.SourceCode;
import me.coley.recaf.ui.ContextMenus;
import me.coley.recaf.ui.controls.view.ClassViewport;
import me.coley.recaf.workspace.JavaResource;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional;

import java.util.Optional;

import static me.coley.recaf.util.JavaParserUtil.*;

/**
 * Context menu handler for {#link JavaPane}.
 *
 * @author Matt
 */
public class JavaContextHandling extends ContextHandling {
	private SourceCode code;

	/**
	 * @param controller
	 * 		Controller to pull info from.
	 * @param codeArea
	 * 		Text editor events originate from.
	 */
	public JavaContextHandling(GuiController controller, CodeArea codeArea) {
		super(controller, codeArea);
		onContextRequest(selection -> {
			if (selection instanceof ClassSelection) {
				ClassSelection cs = (ClassSelection) selection;
				handleClassType(cs.name, cs.dec);
			} else if (selection instanceof MemberSelection){
				MemberSelection ms = (MemberSelection) selection;
				if (ms.method())
					handleMethodType(ms.owner, ms.name, ms.desc, ms.dec);
				else
					handleFieldType(ms.owner, ms.name, ms.desc, ms.dec);
			}
		});
	}

	/**
	 * Goto the selected item's definition.
	 */
	public void gotoSelectedDef() {
		// Get selection
		TwoDimensional.Position pos = codeArea.offsetToPosition(codeArea.getCaretPosition(),
				TwoDimensional.Bias.Backward);
		Object selection = getSelection(pos);
		// Goto class or member definition
		if (selection instanceof ClassSelection) {
			String owner = ((ClassSelection) selection).name;
			JavaResource resource = controller.getWorkspace().getContainingResource(owner);
			controller.windows().getMainWindow().openClass(resource, owner);
		} else if (selection instanceof MemberSelection) {
			MemberSelection ms = (MemberSelection) selection;
			JavaResource resource = controller.getWorkspace().getContainingResource(ms.owner);
			ClassViewport view = controller.windows().getMainWindow().openClass(resource, ms.owner);
			Platform.runLater(() -> view.selectMember(ms.name, ms.desc));
		}
	}

	@Override
	protected Object getSelection(TwoDimensional.Position pos) {
		// Get declaration at point
		Node node = getSelectedNode(code, pos);
		if(node == null)
			return null;
		// Resolve node to some declaration type and display context menu
		if(node instanceof TypeDeclaration) {
			ResolvedReferenceTypeDeclaration dec = ((TypeDeclaration) node).resolve();
			String name = toInternal(dec);
			return new ClassSelection(name, true);
		} else if(node instanceof FieldDeclaration) {
			ResolvedFieldDeclaration dec = ((FieldDeclaration) node).resolve();
			String owner = getOwner(dec);
			String name = dec.getName();
			String desc = getDescriptor(dec.getType());
			return new MemberSelection(owner, name, desc, true);
		} else if(node instanceof MethodDeclaration) {
			ResolvedMethodDeclaration dec = ((MethodDeclaration) node).resolve();
			String owner = getOwner(dec);
			String name = dec.getName();
			String desc = getDescriptor(dec);
			return new MemberSelection(owner, name, desc, true);
		} else if(node instanceof ConstructorDeclaration) {
			ResolvedConstructorDeclaration dec = ((ConstructorDeclaration) node).resolve();
			String owner = toInternal(dec.declaringType());
			String name = "<init>";
			String desc = getDescriptor(dec);
			return new MemberSelection(owner, name, desc, true);
		} else if(node instanceof InitializerDeclaration) {
			InitializerDeclaration dec = (InitializerDeclaration) node;
			if (!dec.getParentNode().isPresent())
				return null; // sanity check, but it should ALWAYS be present and a type declaration
			String owner = toInternal(((TypeDeclaration) dec.getParentNode().get()).resolve());
			String name = "<clinit>";
			String desc = "()V";
			return new MemberSelection(owner, name, desc, true);
		} else if (node instanceof Resolvable<?>) {
			Resolvable<?> r = (Resolvable<?>) node;
			Object resolved = null;
			try {
				resolved = r.resolve();
			} catch(UnsolvedSymbolException ex) {
				return null;
			}
			if (resolved instanceof ResolvedReferenceType) {
				ResolvedReferenceType type = (ResolvedReferenceType) resolved;
				return new ClassSelection(toInternal(type), false);
			} else if (resolved instanceof ResolvedReferenceTypeDeclaration) {
				ResolvedReferenceTypeDeclaration type = (ResolvedReferenceTypeDeclaration) resolved;
				return new ClassSelection(toInternal(type), false);
			} else if (resolved instanceof ResolvedConstructorDeclaration) {
				ResolvedConstructorDeclaration type = (ResolvedConstructorDeclaration) resolved;
				return new ClassSelection(toInternal(type.declaringType()), false);
			} else if (resolved instanceof ResolvedFieldDeclaration) {
				ResolvedFieldDeclaration type = (ResolvedFieldDeclaration) resolved;
				String owner = getOwner(type);
				String name = type.getName();
				String desc = getDescriptor(type);
				return new MemberSelection(owner, name, desc, false);
			} else if (resolved instanceof ResolvedMethodDeclaration) {
				ResolvedMethodDeclaration type = (ResolvedMethodDeclaration) resolved;
				String owner = getOwner(type);
				String name = type.getName();
				String desc = getDescriptor(type);
				return new MemberSelection(owner, name, desc, false);
			}
		}
		return null;
	}

	/**
	 * @param code
	 * 		Analyzed code.
	 */
	public void setCode(SourceCode code) {
		this.code = code;
	}

	/**
	 * @param code
	 * 		Code wrapper.
	 * @param pos
	 * 		Position of caret.
	 *
	 * @return Node of supported type at position.
	 */
	private static Node getSelectedNode(SourceCode code, TwoDimensional.Position pos) {
		// Abort if no analyzed code to parse
		if (code == null)
			return null;
		// Get node at row/column
		Node node = code.getVerboseNodeAt(pos.getMajor() + 1, pos.getMinor());
		// Go up a level until node type is supported
		while(true) {
			if(node instanceof Resolvable || node instanceof InitializerDeclaration)
				break;
			Optional<Node> parent = node.getParentNode();
			if(!parent.isPresent())
				break;
			node = parent.get();
		}
		return node;
	}

	private void handleClassType(String name, boolean declaration) {
		codeArea.setContextMenu(ContextMenus.ofClass(controller, name, declaration));
	}

	private void handleFieldType(String owner, String name, String desc, boolean declaration) {
		codeArea.setContextMenu(ContextMenus.ofField(controller, owner, name, desc, declaration));
	}

	private void handleMethodType(String owner, String name, String desc, boolean declaration) {
		codeArea.setContextMenu(ContextMenus.ofMethod(controller, (JavaPane)
						codeArea.getParent().getParent().getParent().getParent(),
						owner, name, desc, declaration));
	}

	// ===================================== //

	/**
	 * Wrapper for selected classes.
	 */
	private static class ClassSelection {
		private final String name;
		private final boolean dec;

		ClassSelection(String name, boolean dec) {
			this.name = name;
			this.dec = dec;
		}
	}

	/**
	 * Wrapper for selected fields/methods.
	 */
	private static class MemberSelection {
		private final String owner;
		private final String name;
		private final String desc;
		private final boolean dec;

		MemberSelection(String owner, String name, String desc, boolean dec) {
			this.owner = owner;
			this.name = name;
			this.desc = desc;
			this.dec = dec;
		}

		private boolean method() {
			return desc.contains("(");
		}
	}
}