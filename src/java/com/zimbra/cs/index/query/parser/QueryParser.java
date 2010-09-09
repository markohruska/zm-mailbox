/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2010 Zimbra, Inc.
 *
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.3 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.index.query.parser;

import java.io.StringReader;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.apache.lucene.analysis.Analyzer;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.index.query.AddrQuery;
import com.zimbra.cs.index.query.AttachmentQuery;
import com.zimbra.cs.index.query.BuiltInQuery;
import com.zimbra.cs.index.query.ConjQuery;
import com.zimbra.cs.index.query.ConvCountQuery;
import com.zimbra.cs.index.query.ConvQuery;
import com.zimbra.cs.index.query.DateQuery;
import com.zimbra.cs.index.query.DomainQuery;
import com.zimbra.cs.index.query.HasQuery;
import com.zimbra.cs.index.query.InQuery;
import com.zimbra.cs.index.query.ItemQuery;
import com.zimbra.cs.index.query.ModseqQuery;
import com.zimbra.cs.index.query.Query;
import com.zimbra.cs.index.query.SenderQuery;
import com.zimbra.cs.index.query.SizeQuery;
import com.zimbra.cs.index.query.SubQuery;
import com.zimbra.cs.index.query.SubjectQuery;
import com.zimbra.cs.index.query.TagQuery;
import com.zimbra.cs.index.query.TextQuery;
import com.zimbra.cs.index.query.TypeQuery;
import com.zimbra.cs.mailbox.MailServiceException;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.service.util.ItemId;

/**
 * Parser for search query.
 *
 * @author ysasaki
 */
public final class QueryParser implements ParserConstants, ParserTreeConstants {

    private static final Map<String, Integer> str2jj = new HashMap<String, Integer>();
    static {
        for (int i = 0; i < tokenImage.length; i++) {
            String token = tokenImage[i];
            if (token.startsWith("\"") || token.endsWith(":\"")) {
                str2jj.put(token.substring(1, token.length() - 1), i);
            }
        }
    }

    private static final Map<String, Integer> folder2id =
        new ImmutableMap.Builder<String, Integer>()
        .put("inbox", Mailbox.ID_FOLDER_INBOX)
        .put("trash", Mailbox.ID_FOLDER_TRASH)
        .put("junk", Mailbox.ID_FOLDER_SPAM)
        .put("sent", Mailbox.ID_FOLDER_SENT)
        .put("drafts", Mailbox.ID_FOLDER_DRAFTS)
        .put("contacts", Mailbox.ID_FOLDER_CONTACTS)
        .build();

    private final Mailbox mailbox;
    private final Analyzer analyzer;
    private TimeZone timezone = TimeZone.getTimeZone("UTC");
    private Locale locale = Locale.ENGLISH;
    private int defaultField = CONTENT;
    private String sortBy;

    /**
     * Constructs a new {@link QueryParser}.
     *
     * @param mbox mailbox to search
     * @param analyzer Lucene analyzer
     */
    public QueryParser(Mailbox mbox, Analyzer analyzer) {
        this.mailbox = mbox;
        this.analyzer = analyzer;
    }

    /**
     * Sets the current time zone for date-time translation.
     *
     * @param value time zone
     */
    public void setTimeZone(TimeZone value) {
        timezone = value;
    }

    /**
     * Sets the current locale for date-time format.
     *
     * @param value locale
     */
    public void setLocale(Locale value) {
        locale = value;
    }

    /**
     * Sets the default field in case field name is omitted.
     *
     * @param name field name
     * @throws ServiceException if the name is invalid
     */
    public void setDefaultField(String name) throws ServiceException {
        Integer jj = str2jj.get(name);
        if (jj == null) {
            throw MailServiceException.QUERY_PARSE_ERROR(
                    name, null, name, -1, "UNKNOWN_QUERY_TYPE");
        }
        defaultField = jj;
    }

    /**
     * Returns the sort field detected in the query string.
     * <p>
     * The value is set after {@link #parse(String)}.
     *
     * @return sort field
     */
    public String getSortBy() {
        return sortBy;
    }

    /**
     * Parses the query string.
     *
     * @param src query string
     * @return query clauses
     * @throws ServiceException if a grammar error detected
     */
    public List<Query> parse(String src) throws ServiceException {
        Parser parser = new Parser(new StringReader(src));
        SimpleNode node;
        try {
            node = parser.parse();
        } catch (TokenMgrError e) {
            throw MailServiceException.QUERY_PARSE_ERROR(src, e,
                    "", -1, e.getMessage());
        } catch (ParseException e) {
            throw MailServiceException.QUERY_PARSE_ERROR(src, e,
                    e.currentToken.image, e.currentToken.beginColumn, e.getMessage());
        }

        assert(node.id == JJTROOT);
        assert(node.jjtGetNumChildren() == 1);

        try {
            return toQuery((SimpleNode) node.jjtGetChild(0));
        } catch (QueryParserException e) {
            throw MailServiceException.QUERY_PARSE_ERROR(src, e,
                    e.getText(), e.getErrorOffset(), e.getMessage());
        }
    }

    private List<Query> toQuery(SimpleNode node) throws QueryParserException, ServiceException {
        assert(node.id == JJTQUERY);

        List<Query> result = new LinkedList<Query>();
        ConjQuery conj = null;
        for (int i = 0; i < node.jjtGetNumChildren(); i++) {
            SimpleNode child = (SimpleNode) node.jjtGetChild(i);
            switch (child.id) {
                case JJTCONJUNCTION:
                    conj = toConjunction(child);
                    break;
                case JJTCLAUSE:
                    if (!result.isEmpty()) {
                        if (conj == null) {
                            result.add(new ConjQuery(AND));
                        } else {
                            result.add(conj);
                            conj = null;
                        }
                    }
                    result.add(toClause(child));
                    break;
                case JJTSORTBY:
                    processSortBy(child);
                    break;
                default:
                    assert(false);
            }
        }
        return result;
    }

    private Query toClause(SimpleNode node) throws QueryParserException, ServiceException {
        assert(node.id == JJTCLAUSE);
        int num = node.jjtGetNumChildren();
        assert(num > 0 && num <= 2);

        int mod = node.jjtGetNumChildren() > 1 ?
                toModifier((SimpleNode) node.jjtGetChild(0)) : 0;

        SimpleNode child = (SimpleNode) node.jjtGetChild(num - 1);
        switch (child.id) {
            case JJTTEXTCLAUSE:
                return toTextClause(mod, child);
            case JJTITEMCLAUSE:
                return toItemClause(mod, child);
            case JJTDATECLAUSE:
                return toDateClause(mod, child);
            case JJTQUERY:
                return toSubQuery(mod, child);
            case JJTDEFAULTCLAUSE:
                return toDefaultClause(mod, child);
            default:
                assert(false);
                return null;
        }
    }

    private Query toSubQuery(int mod, SimpleNode node)
        throws QueryParserException, ServiceException {
        assert(node.id == JJTQUERY);
        return new SubQuery(mod, toQuery(node));
    }

    private Query toTextClause(int mod, SimpleNode node)
        throws QueryParserException, ServiceException {

        assert(node.id == JJTTEXTCLAUSE);
        assert(node.jjtGetNumChildren() == 1);

        return toTerm(mod, node.jjtGetFirstToken(),
                (SimpleNode) node.jjtGetChild(0));
    }

    private Query toDefaultClause(int mod, SimpleNode node)
        throws QueryParserException, ServiceException {

        assert(node.id == JJTDEFAULTCLAUSE);

        return createQuery(mod, Token.newToken(defaultField),
                node.jjtGetFirstToken(), toString(node));
    }

    private Query toItemClause(int mod, SimpleNode node)
        throws QueryParserException, ServiceException {

        assert(node.id == JJTITEMCLAUSE);
        assert(node.jjtGetNumChildren() == 1);

        return toTerm(mod, node.jjtGetFirstToken(),
                (SimpleNode) node.jjtGetChild(0));
    }

    private Query toDateClause(int mod, SimpleNode node)
        throws QueryParserException, ServiceException {

        assert(node.id == JJTDATECLAUSE);
        assert(node.jjtGetNumChildren() == 1);

        return toTerm(mod, node.jjtGetFirstToken(),
                (SimpleNode) node.jjtGetChild(0));
    }

    private Query toTerm(int mod, Token field, SimpleNode node)
        throws QueryParserException, ServiceException {

        assert(node.id == JJTDATETERM || node.id == JJTTEXTTERM ||
                node.id == JJTITEMTERM);

        if (node.jjtGetNumChildren() == 0) {
            Token token = node.jjtGetFirstToken();
            return createQuery(mod, field, token, toString(node));
        } else {
            List<Query> sub = new LinkedList<Query>();
            ConjQuery conj = null;
            int submod = 0;
            for (int i = 0; i < node.jjtGetNumChildren(); i++) {
                SimpleNode child = (SimpleNode) node.jjtGetChild(i);
                switch (child.id) {
                    case JJTMODIFIER:
                        submod = toModifier(child);
                        break;
                    case JJTTEXTTERM:
                    case JJTITEMTERM:
                    case JJTDATETERM:
                        if (!sub.isEmpty()) {
                            if (conj == null) {
                                sub.add(new ConjQuery(AND));
                            } else {
                                sub.add(conj);
                                conj = null;
                            }
                        }
                        sub.add(toTerm(submod, field, child));
                        submod = 0;
                        break;
                    case JJTCONJUNCTION:
                        conj = toConjunction(child);
                        break;
                    default:
                        assert(false);
                }
            }
            return new SubQuery(mod, sub);
        }
    }

    private String toString(SimpleNode node) {
        assert(node.jjtGetNumChildren() == 0);

        switch (node.id) {
            case JJTTEXTTERM:
            case JJTITEMTERM:
            case JJTDEFAULTCLAUSE:
                return toString(node.jjtGetFirstToken());
            case JJTDATETERM:
                Token token = node.jjtGetFirstToken();
                switch (token.kind) {
                    case PLUS:
                    case MINUS:
                        return token.image + toString(token.next);
                    default:
                        return toString(token);
                }
            default:
                assert(false);
                return "";
        }

    }

    private String toString(Token token) {
        switch (token.kind) {
            case TERM:
                return token.image;
            case QUOTED_TERM:
            case BRACED_TERM:
                // trim
                return token.image.substring(1, token.image.length() - 1);
            default:
                assert(false);
                return "";
        }
    }

    private ConjQuery toConjunction(SimpleNode node) {
        assert(node.id == JJTCONJUNCTION);
        assert(node.jjtGetFirstToken().kind == AND ||
                node.jjtGetFirstToken().kind == OR);
        return new ConjQuery(node.jjtGetFirstToken().kind);
    }

    private int toModifier(SimpleNode node) {
        assert(node.id == JJTMODIFIER);
        switch (node.jjtGetFirstToken().kind) {
            case PLUS:
                return PLUS;
            case MINUS:
            case NOT:
                return MINUS;
            default:
                return 0;
        }
    }

    private void processSortBy(SimpleNode node) {
        assert(node.id == JJTSORTBY);
        sortBy = node.jjtGetFirstToken().next.image;
    }

    private Query createQuery(int mod, Token field, Token term, String text)
        throws QueryParserException, ServiceException {

        switch (field.kind) {
          case HAS:
            if (text.equalsIgnoreCase("attachment")) {
                return new AttachmentQuery(mailbox, mod, "any");
            } else {
                return new HasQuery(mailbox, mod, text);
            }
          case ATTACHMENT:
            return new AttachmentQuery(mailbox, mod, text);
          case TYPE:
            return new TypeQuery(mailbox, mod, text);
          case ITEM:
            return ItemQuery.Create(mailbox, mod, text);
          case UNDERID:
          case INID: {
              ItemId iid = null;
              int subfolderSplit = text.indexOf('/');
              String iidStr;
              String subfolderPath = null;
              if (subfolderSplit > 0) {
                  iidStr = text.substring(0, subfolderSplit);
                  subfolderPath = text.substring(subfolderSplit+1);
              } else {
                  iidStr = text;
              }
              iid = new ItemId(iidStr, (String) null);
              try {
                  return InQuery.Create(mailbox, mod, iid, subfolderPath, (field.kind == UNDERID));
              } catch (ServiceException e) {
                  // bug: 18623 -- dangling mountpoints create problems with 'is:remote'
                  ZimbraLog.index.debug("Ignoring INID/UNDERID clause b/c of ServiceException", e);
                  return InQuery.Create(mailbox, mod, InQuery.IN_NO_FOLDER, false);
              }
          }
          case UNDER:
          case IN: {
              Integer folderId = folder2id.get(text.toLowerCase());
              if (folderId != null) {
                  return InQuery.Create(mailbox, mod, folderId, (field.kind == UNDER));
              } else {
                  return InQuery.Create(mailbox, mod, text, (field.kind == UNDER));
              }
          }
          case TAG:
              return new TagQuery(mailbox, mod, text, true);
          case IS:
              try {
                  return BuiltInQuery.getQuery(text.toLowerCase(), mailbox, analyzer, mod);
              } catch (IllegalArgumentException e) {
                  throw new QueryParserException("UNKNOWN_TEXT_AFTER_IS", term);
              }
          case CONV:
              return ConvQuery.create(mailbox, mod, text);
          case CONV_COUNT:
              return ConvCountQuery.create(mod, field.kind, text);
          case DATE:
          case DAY:
          case WEEK:
          case MONTH:
          case YEAR:
          case AFTER:
          case BEFORE:
          case CONV_START:
          case CONV_END:
          case APPT_START:
          case APPT_END: {
              DateQuery query = new DateQuery(field.kind);
              query.parseDate(text, timezone, locale);
              return query;
          }
          case TOFROM:
          case TOCC:
          case FROMCC:
          case TOFROMCC:
              if (Strings.isNullOrEmpty(text)) {
                 throw new QueryParserException("MISSING_TEXT_AFTER_TOFROMCC", term);
              }
              return AddrQuery.createFromTarget(mailbox, analyzer, mod, field.kind, text);
          case FROM:
              if (Strings.isNullOrEmpty(text)) {
                  throw new QueryParserException("MISSING_TEXT_AFTER_TOFROMCC", term);
              }
              return SenderQuery.create(mailbox, analyzer, mod, field.kind, text);
          case TO:
          case ENVTO:
          case ENVFROM:
          case CC:
              if (Strings.isNullOrEmpty(text)) {
                  throw new QueryParserException("MISSING_TEXT_AFTER_TOFROMCC", term);
              }
              if (text.startsWith("@")) {
                  return new DomainQuery(mailbox, mod, field.kind, text);
              }
            return new TextQuery(mailbox, analyzer, mod, field.kind, text);
          case MODSEQ:
            return new ModseqQuery(mod, field.kind, text);
          case SIZE:
          case BIGGER:
          case SMALLER:
              return new SizeQuery(mod, field.kind, text);
          case SUBJECT:
              return SubjectQuery.create(mailbox, analyzer, mod, field.kind, text);
          case FIELD:
              int open = field.image.indexOf('[');
              if (open >= 0) {
                  int close = field.image.indexOf(']');
                  if (close >= 0 && close > open) {
                      text = field.image.substring(open + 1, close) + ":" + text;
                  }
              } else if (field.image.charAt(0) == '#') {
                  text = field.image.substring(1) + text;
              }

              return new TextQuery(mailbox, analyzer, mod, field.kind, text);
          default:
              return new TextQuery(mailbox, analyzer, mod, field.kind, text);
        }
    }
}
