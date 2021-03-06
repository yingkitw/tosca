// Copyright (c) 2015 IBM Corporation.

package org.transscript.antlr;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DiagnosticErrorListener;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenSource;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.transscript.parser.TransScriptMetaLexer;
import org.transscript.parser.TransScriptMetaParser;
import org.transscript.runtime.ConstructionDescriptor;
import org.transscript.runtime.Variable;
import org.transscript.tool.MutableInt;

import net.sf.crsx.CRS;
import net.sf.crsx.CRSException;
import net.sf.crsx.Constructor;
import net.sf.crsx.Sink;
import net.sf.crsx.generic.GenericFactory;
import net.sf.crsx.util.Buffer;
import net.sf.crsx.util.ExtensibleMap;
import net.sf.crsx.util.LinkedExtensibleMap;

/**
 * Create TransScript term from ANTLR parse events.
 * 
 * <p>
 * Temporarily support CRSX3 sink until the ANTLR-based parser generator is ported to TransScript
 * 
 * @author Lionel Villard
 */
public class SinkAntlrListener implements ParseTreeListener
{
	// Static helper

	public static void fireEnterZOM(List<ParseTreeListener> listeners, ParserRuleContext _ctx)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).enterZOM(_ctx));
	}

	public static void fireExitZOM(List<ParseTreeListener> listeners, ParserRuleContext _ctx)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).exitZOM(_ctx));
	}

	public static void fireEnterOPT(List<ParseTreeListener> listeners, ParserRuleContext _ctx)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).enterOPT(_ctx));
	}

	public static void fireExitOPT(List<ParseTreeListener> listeners, ParserRuleContext _ctx)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).exitOPT(_ctx));
	}

	public static void fireEnterAlt(List<ParseTreeListener> listeners, ParserRuleContext _ctx)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).enterAlt(_ctx));
	}

	public static void fireEnterAlt(List<ParseTreeListener> listeners, ParserRuleContext _ctx, String name)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).enterAlt(_ctx, name));
	}

	public static void fireExitAlt(List<ParseTreeListener> listeners, ParserRuleContext _ctx)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).exitAlt(_ctx));
	}

	public static void fireHide(List<ParseTreeListener> listeners, ParserRuleContext _ctx)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).hide(_ctx));
	}

	public static void fireTerm(List<ParseTreeListener> listeners, ParserRuleContext _ctx)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).term(_ctx));
	}

	public static void fireTerm(List<ParseTreeListener> listeners, ParserRuleContext _ctx, String type)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).term(_ctx, type));
	}

	public static void fireTail(List<ParseTreeListener> listeners, ParserRuleContext _ctx)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).tail(_ctx));
	}

	public static void fireEmbed(List<ParseTreeListener> listeners, ParserRuleContext _ctx)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).embed(_ctx));
	}

	public static void fireEnterSymbol(List<ParseTreeListener> listeners, ParserRuleContext _ctx)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).enterSymbol(_ctx));
	}

	public static void fireExitSymbol(List<ParseTreeListener> listeners, ParserRuleContext _ctx)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).exitSymbol(_ctx));
	}

	public static void fireEnterBinder(List<ParseTreeListener> listeners, ParserRuleContext _ctx, String name)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).enterBinder(_ctx, name));
	}

	public static void fireExitBinder(List<ParseTreeListener> listeners, ParserRuleContext _ctx)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).exitBinder(_ctx));
	}

	public static void fireEnterBinds(List<ParseTreeListener> listeners, ParserRuleContext _ctx, String names)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).enterBinds(_ctx, names));
	}

	public static void fireExitBinds(List<ParseTreeListener> listeners, ParserRuleContext _ctx)
	{
		fire(listeners, _ctx, l -> ((SinkAntlrListener) l).exitBinds(_ctx));
	}

	private static void fire(List<ParseTreeListener> listeners, ParserRuleContext _ctx, Consumer<ParseTreeListener> apply)
	{
		if (listeners != null)
			listeners.stream().filter(l -> l instanceof SinkAntlrListener).forEach(apply);
	}

	// Variable stack marker
	final static private Object MARKER = new Object();

	// Some enums

	enum State {
		PARSE, START_EMBED, PARSE_EMBED, NAME, SKIP
	}

	enum TokenSort {
		STRING, NUMERIC, TERM
	}

	// The state.

	/** The CRSX3 sink */
	private Sink sink;

	/** The CRSX3 list constructors */
	private Constructor cons;
	private Constructor nil;

	/** The CRSX4 sink */
	private org.transscript.runtime.Sink sink4;

	/** The List construction descriptors */
	final protected ConstructionDescriptor nilDesc;
	final protected ConstructionDescriptor consDesc;

	private GenericFactory factory;
	private ArrayDeque<MutableInt> consCount;
	private ArrayDeque<ParserRuleContext> ruleContext;

	/** The ANTLR4 parser */
	private Parser parser;

	/** Constructor name prefix */
	private String prefix;

	/** Language specific meta variable prefix */
	private String metachar;

	/** Whether the next token represent the tail of a list. */
	private boolean tail;

	/** When non-null, indicates received tokens are parts of a name, to associate to this id */
	private String binderId;

	/** Name being constructed. Whitespace are ignored. */
	private String binderName;

	/** Map binder id to binder name */
	private HashMap<String, String> binderNames;

	/** In scope bound variables. */
	private ArrayDeque<Object> bounds;

	/** In scope fresh variables. */
	private ArrayDeque<Object> freshes;

	/** Current token sort */
	private TokenSort sort;

	/** Meta term type */
	private String termType;

	/** Listener state? */
	private State state;

	/** Is embedded code crsx4?  */
	private boolean embedCrsx4;

	/**
	 * Create an crsx ANTLR listener for CRSX3
	 * @param factory
	 * @param sink
	 * @param prefix    Prefix to apply to constructor names
	 * @param metachar  Language specific meta variable prefix
	 * @param parser
	 */
	public SinkAntlrListener(GenericFactory factory, Sink sink, String prefix, String metachar, Parser parser,
			Map<String, org.transscript.runtime.Variable> bounds)
	{
		this.factory = factory;
		this.sink = sink;
		this.consCount = new ArrayDeque<>();
		this.ruleContext = new ArrayDeque<>();

		this.cons = sink.makeConstructor("$Cons");
		this.nil = sink.makeConstructor("$Nil");
		this.parser = parser;
		this.prefix = prefix;
		this.metachar = metachar;
		this.state = State.PARSE;
		this.sort = TokenSort.STRING;

		this.binderNames = new HashMap<>();
		this.bounds = new ArrayDeque<>();
		if (bounds != null)
			this.bounds.addAll(bounds.values());
		this.freshes = new ArrayDeque<>();

		this.embedCrsx4 = prefix.equals("Text4_");

		this.nilDesc = null;
		this.consDesc = null;

	}

	/**
	 * Create an crsx ANTLR listener for CRSX4
	 * @param factory
	 * @param sink
	 * @param prefix    Prefix to apply to constructor names
	 * @param metachar  Language specific meta variable prefix
	 * @param parser
	 */
	public SinkAntlrListener(org.transscript.runtime.Sink sink, String prefix, String metachar, Parser parser,
			Map<String, org.transscript.runtime.Variable> bounds)
	{
		this.sink4 = sink;
		this.consCount = new ArrayDeque<>();
		this.ruleContext = new ArrayDeque<>();

		this.parser = parser;
		this.prefix = prefix;
		this.metachar = metachar;
		this.state = State.PARSE;
		this.sort = TokenSort.STRING;

		this.binderNames = new HashMap<>();
		this.bounds = new ArrayDeque<>();
		if (bounds != null)
			this.bounds.addAll(bounds.values());
		this.freshes = new ArrayDeque<>();

		this.embedCrsx4 = prefix.equals("Text4_") || prefix.equals("TransScript_");

		this.nilDesc = sink.context().lookupDescriptor("Nil");
		this.consDesc = sink.context().lookupDescriptor("Cons");

	}

	/**
	 *  Add location properties to constructor
	 */
	protected Constructor locate(Token token, Constructor c)
	{
		return c;

		// No location until crsx4 can compile crsx4

		//		int column = token.getCharPositionInLine();
		//		int line = token.getLine();
		//		return Util.wrapWithLocation(sink, c, parser.getInputStream().getSourceName(), line, column);
	}

	/**
	 *  Send location properties 
	 */
	protected void sendLocation(Token token)
	{

		// No location until crsx4 can compile crsx4

		//		int column = token.getCharPositionInLine();
		//		int line = token.getLine();
		//		return Util.wrapWithLocation(sink, c, parser.getInputStream().getSourceName(), line, column);
	}

	/**
	 * Receive the notification the next sequence of tokens are list items.
	 * 
	 * <p>Constructs nested Cons(..., ...) and Nil terms. 
	 * 
	 * @param context
	 */
	public void enterZOM(ParserRuleContext context)
	{
		consCount.push(new MutableInt(0));
		tail = false;
	}

	public void exitZOM(ParserRuleContext context)
	{
		if (!tail)
		{
			if (sink != null)
				sink = sink.start(nil).end();
			else
				sink4 = sink4.start(nilDesc).end();
		}

		int count = consCount.pop().v;

		if (sink != null)
		{
			while (count-- > 0)
				sink = sink.end();
		}
		else
		{
			while (count-- > 0)
				sink4 = sink4.end();
		}
		tail = false;
	}

	public void enterOPT(ParserRuleContext context)
	{
		enterZOM(context);
	}

	public void exitOPT(ParserRuleContext context)
	{
		exitZOM(context);
	}

	public void enterAlt(ParserRuleContext context)
	{
		ParserRuleContext parentCtx = ruleContext.peek();
		String ruleName = parser.getRuleNames()[parentCtx.getRuleIndex()];

		if (sink != null)
			sink = sink.start(locate(parentCtx.getStart(), sink.makeConstructor(prefix + ruleName)));
		else
		{
			sendLocation(parentCtx.getStart());
			sink4 = sink4.start(sink4.context().lookupDescriptor(prefix + ruleName));
		}
	}

	public void enterAlt(ParserRuleContext context, String name)
	{
		ParserRuleContext parentCtx = ruleContext.peek();
		String ruleName = parser.getRuleNames()[parentCtx.getRuleIndex()];

		if (sink != null)
			sink = sink.start(locate(parentCtx.getStart(), sink.makeConstructor(prefix + ruleName + "_A" + name)));
		else
		{
			sendLocation(parentCtx.getStart());
			sink4 = sink4.start(sink4.context().lookupDescriptor(prefix + ruleName + "_A" + name));
		}
	}

	public void exitAlt(ParserRuleContext context)
	{
		if (sink != null)
			sink = sink.end();
		else
			sink4 = sink4.end();

	}

	public void embed(ParserRuleContext context)
	{
		state = State.START_EMBED;
	}

	/** Receive the notification the next token is of type term */
	public void term(ParserRuleContext context)
	{
		sort = TokenSort.TERM;
	}

	/** Receive the notification the next token is of type term */
	public void term(ParserRuleContext _ctx, String type)
	{
		termType = type;
		sort = TokenSort.TERM;
	}

	/** Receive the notification the next token match all of a list tail */
	public void tail(ParserRuleContext context)
	{
		tail = true;
	}

	/**
	 * Hide next terminal
	 * @param context
	 */
	public void hide(ParserRuleContext context)
	{
		state = State.SKIP;
	}

	/**
	 * Receive the notification the next tokens are part of a binder name
	 * @param context
	 * @param name to associate to the binder
	 */
	public void enterBinder(ParserRuleContext context, String name)
	{
		assert!tail : "Cannot declare a binder is a list tail";
		assert binderId == null : "Cannot nest binders";

		state = State.NAME;
		binderId = name.trim();
		binderName = "";
	}

	/**
	 * Receive the notification the binder name is complete
	 * @param context
	 */
	public void exitBinder(ParserRuleContext context)
	{
		assert state == State.NAME;
		assert!tail : "Cannot declare a binder is a list tail";
		assert binderId != null : "Missing enterBinder notification";

		binderNames.put(binderId, binderName);
		binderId = null;
		binderName = null;
		state = State.PARSE;
	}

	/**
	 * Receive the notification the next tokens declare a binder
	 * @param context
	 */
	public void enterSymbol(ParserRuleContext context)
	{
		assert!tail : "Cannot declare a binder is a list tail";
		assert binderId == null : "Cannot nest binders";

		binderName = "";
		state = State.NAME;
	}

	/**
	 * Receive the notification all tokens parts of a binder name have been received
	 * @param context
	 */
	public void exitSymbol(ParserRuleContext context)
	{
		assert state == State.NAME;
		assert!tail : "Cannot declare a name in a list tail";

		if (sort == TokenSort.TERM)
		{
			// received a metavariable. 
			String metaname = fixupMetachar(binderName);
			if (sink != null)
				sink = sink.startMetaApplication(metaname).endMetaApplication();
			else
			{
				//		sink4 = sink4.startMetaApplication(metaname).endMetaApplication();
				//	if (termType != null)
				//		sink4 = sink4.startType().literal(termType).endType();
			}

			sort = TokenSort.STRING;
		}
		else
		{
			// This is a binder occurrence. Resolve and emit
			assert bounds != null;
			Optional<Object> variable = bounds.stream().filter(var -> {
				if (var == MARKER)
					return false;

				if (sink == null)
					return ((Variable) var).name().equals(binderName);
				else
					return ((net.sf.crsx.Variable) var).name().equals(binderName);
			}).findFirst();

			if (!variable.isPresent())
			{
				// Try among fresh variables
				variable = freshes.stream().filter(var -> {
					if (sink == null)
						return ((Variable) var).name().equals(binderName);
					else
						return ((net.sf.crsx.Variable) var).name().equals(binderName);

				}).findFirst();
			}

			if (!variable.isPresent())
			{
				// Create new fresh variable.
				if (sink == null)
					variable = Optional.of(sink4.context().makeVariable(binderName));
				else
					variable = Optional.of(sink.makeVariable(binderName, false));

				freshes.push(variable.get());
			}

			// Can now emit variable
			if (sink == null)
				sink4 = sink4.use((Variable) variable.get());
			else
				sink = sink.use((net.sf.crsx.Variable) variable.get());

		}
		state = State.PARSE;
	}

	/**
	 * Binds the name associated to the given identifier
	 * @param context
	 * @param id space-separated ids.
	 */
	public void enterBinds(ParserRuleContext context, String names)
	{
		String[] snames = names.trim().split(" ");
		Object[] binders = sink == null ? new Variable[snames.length] : new net.sf.crsx.Variable[snames.length];

		bounds.add(MARKER);
		for (int i = 0; i < snames.length; i++)
		{
			String id = snames[i];
			String name = binderNames.remove(id); // consume binder name 
			assert name != null : "Invalid grammar: binds used without binder/name";

			if (sink == null)
				binders[i] = sink4.context().makeVariable(name);
			else
				binders[i] = factory.makeVariable(name, false);

			bounds.push(binders[i]);
		}

		if (sink == null)
		{
			for (int i = 0; i < binders.length; i++)
				sink4 = sink4.bind((Variable) binders[i]);
		}
		else
			sink = sink.binds((net.sf.crsx.Variable[]) binders);
	}

	/**
	 * Unbinds last bound group of binders.
	 * @param context
	 */
	public void exitBinds(ParserRuleContext context)
	{
		assert!bounds.isEmpty() : "Unbalanced use of enterBinds/exitBinds";

		while (bounds.pop() != MARKER);
	}

	// Overrides

	@Override
	public void enterEveryRule(ParserRuleContext context)
	{
		// Is that a rule part of a list?
		if (!consCount.isEmpty() && consCount.peek() != MutableInt.MARKER)
		{
			if (!tail)
			{
				if (sink != null)
					sink = sink.start(cons);
				else
					sink4 = sink4.start(consDesc);

				consCount.peek().v++;
			}
			else
			{
				// Following events fill the second Cons argument
			}
		}

		consCount.push(MutableInt.MARKER);
		ruleContext.push(context);
	}

	@Override
	public void exitEveryRule(ParserRuleContext context)
	{
		consCount.pop();
		ruleContext.pop();
	}

	@Override
	public void visitErrorNode(ErrorNode arg0)
	{}

	@Override
	public void visitTerminal(TerminalNode context)
	{
		switch (state)
		{
			case SKIP :
				state = State.PARSE;
				break;
			case PARSE :
				if (context.getSymbol().getType() != -1)
				{
					// Is that a terminal part of a list?
					if (!consCount.isEmpty() && consCount.peek() != MutableInt.MARKER)
					{
						if (!tail)
						{
							if (sink != null)
								sink = sink.start(cons);
							else
								sink4 = sink4.start(consDesc);

							consCount.peek().v++;
						}
					}

					switch (sort)
					{
						case NUMERIC :
						case STRING :
							if (sink != null)
								sink = sink.start(
										locate(context.getSymbol(), sink.makeLiteral(context.getText(), CRS.STRING_SORT))).end();
							else
							{
								sendLocation(context.getSymbol());

								String t = context.getText();

								// HACK: should not unquote here!
								if (t.length() > 0 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"')
									t = t.substring(1).substring(0, t.length() - 2);

								sink4 = sink4.literal(t);
							}
							break;
						case TERM :
							String metaname = fixupMetachar(context.getText());

							if (sink != null)
								sink = sink.startMetaApplication(metaname);
							else
							{
								//					sink4 = sink4.startMetaApplication(metaname);
							}

							// Add directly bound variable.
							// REVISIT: should be user-specified.
							for (Object variable : bounds)
							{
								if (variable == MARKER)
									break;
								if (sink != null)
									sink = sink.use((net.sf.crsx.Variable) variable);
								else
									sink4 = sink4.use((Variable) variable);
							}

							if (sink != null)
								sink = sink.endMetaApplication();
							else
							{
								//				sink4 = sink4.endMetaApplication();
								//				if (termType != null)
								//					sink4 = sink4.startType().literal(termType).endType();
							}
							break;
						default :
							break;
					}

					sort = TokenSort.STRING;

				}
				break;
			case START_EMBED :
				// Just the category/sort name. Ignore
				state = State.PARSE_EMBED;
				break;
			case PARSE_EMBED :
				// Recursively parse this token
				Token token = context.getSymbol();
				String text = context.getText();
				if (text.length() > 1)
				{
					// Last character is closing the embedded section: trim it.
					text = text.trim();
					text = text.substring(0, text.length() - 1);

					Reader reader = new StringReader(text);

					if (sink != null)
					{

						try
						{
							sink = factory.parser(factory).parse(
									sink, null, reader, "", token.getLine(), token.getCharPositionInLine(), toCrsx3Bound());
						}
						catch (CRSException | IOException e)
						{
							throw new RuntimeException(e);
						}

					}
					 
				}
				state = State.PARSE;
				break;

			case NAME :
				// Receive a symbol or a bound variable
				binderName += context.getText().trim();
				break;

			default :
				break;
		}
	}
 

	/**
	 * Convert bound variable structure to one compatible with crsx3
	 * @return
	 */
	private ExtensibleMap<String, net.sf.crsx.Variable> toCrsx3Bound()
	{
		ExtensibleMap<String, net.sf.crsx.Variable> map = new LinkedExtensibleMap<>();
		for (Object v : bounds)
		{
			if (v instanceof net.sf.crsx.Variable)
				map = map.extend(((net.sf.crsx.Variable) v).name(), (net.sf.crsx.Variable) v);
		};
		for (Object v : freshes)
		{
			if (v instanceof net.sf.crsx.Variable)
				map = map.extend(((net.sf.crsx.Variable) v).name(), (net.sf.crsx.Variable) v);
		};
		return map;
	}

	/**
	 * Convert parser specific metacharacter to Crsx meta character (#).
	 */
	protected String fixupMetachar(String metavar)
	{
		return "#" + metavar.substring(metachar.length());
	}
	//
	//	/**
	//	 * Convert raw type to proper TransScript type.
	//	 */
	//	private String fixupType(String type)
	//	{
	//		if (type.endsWith("_TOK"))
	//			return "String";
	//
	//		final boolean islist = type.endsWith("_OOM") || type.endsWith("_ZOM") || type.endsWith("_OPT");
	//		type = islist ? type.substring(0, type.length() - "_ZOM".length()) : type;
	//
	//		return (islist ? "List<" : "") + prefix + type + "_sort" + (islist ? ">" : "");
	//	}

}
