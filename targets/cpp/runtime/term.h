// Copyright (c) 2016 IBM Corporation.

#ifndef _TERM_H
#define _TERM_H

#include <cassert>
#include <string>

#include "compat.h"

class _Context;
typedef _Context& Context;

class _Ref;
typedef _Ref& Ref;


typedef Ref (*Function)();
typedef Ref (*Fun0)(Context ctx);
typedef Ref (*Fun1)(Context ctx, Ref);
typedef Ref (*Fun2)(Context ctx, Ref, Ref);
typedef Ref (*Fun3)(Context ctx, Ref, Ref, Ref);
typedef Ref (*Fun4)(Context ctx, Ref, Ref, Ref, Ref);


//
//
//namespace ts
//{
//
//namespace runtime
//{

class _Variable;

//using Variable = _Variable&;
typedef _Variable& Variable;

class _Term;
//using Term = _Term&;
typedef _Term& Term;


class _Ref
{
public:
    /**
     * Number of references to this instance.
     */
    unsigned long refcount;

public:
    _Ref();
    virtual ~_Ref();

    /** Add new ref */
    void Ref();

    /** Release ref */
    void Release();

};

/* Just a convenient function for the user code to look nicer */
template<typename T>
inline T& NewRef(T& ref)
{
    ref.Ref();
    return static_cast<T&>(ref);
}

/*
 * Base class for all terms, typed or not.
 */
class _Term: public _Ref
{
public:
    /**
     * @return shallow copy of this term.
     */
    //  virtual Term Copy(Context c) = 0;
    /** @return true when this term is data */
    virtual bool Data() const
    {
        return true;
    }

    /**
     * Peek at the ith subterm.
     *
     * @param i the sub index
     * @return a subterm or null if none at the given index. does not create a new reference.
     */
    virtual Optional<_Term> Sub(int i) const
    {
        return Optional<_Term>::nullopt;
    }

    /**
     * Replace the ith sub term
     *
     * @param i the sub index. Must be >=0 and < number of subs
     * @param term the term. The reference is transferred.
     */
    virtual void SetSub(int i, Term sub)
    {
        assert(false);
    }

    /**
     * Get binders of the ith subterm.
     *
     * @param index
     * @return a binder, or null.
     */
    virtual Optional<_Variable> Binder(int i, int j)
    {
        return Optional<_Variable>::nullopt;
    }

    /**
     * Set jth binder of the ith subterm.
     *
     * @param i subterm index. Must be >=0 and < number of subs
     * @param j subbinder index.  Must be >=0 and < number of binders for the given sub
     */
    virtual void SetBinder(int i, int j, Variable var)
    {
        assert(false);
    }

    /**
     * Evaluates thunk (if needed).
     *
     * The reference to this term is consumed.
     *
     * @param context
     * @return A new reference to the evaluated term. It might still be a thunk if the evaluation has been interrupted
     */
    virtual Term eval(Context c)
    {
        return *this;
    }

    // TODO: use C++11 concepts to restrict T to extends Term
    template<typename T>
    static T Subst(Context c, T term, std::initializer_list<Term> from, std::initializer_list<Term> to);

};
// _Term

/* Unevaluated function (thunk) */
template<typename T>
class _LazyTerm: public _Term
{
public:
    _LazyTerm(Function f) :
            function(f), value(Optional<T>::nullopt)
    {
    }

    /** @return true when this term is data */
    bool Data() const
    {
        return function == 0 ? reinterpret_cast<_Term&>(value.value()).Data() : false;
    }

    T eval(Context c)
    {
        if (!value)
        {
            value = function(c); // Acquire ref.
            function = 0;
        }
        value.value().Ref();
        return value.value();
    }

protected:
    // the unevaluated value.
    Function function;

    // the evaluated value.
    Optional<T> value;

    _LazyTerm(T value) :
            function(0)
    {
        value = make_optional(value);
    }

};

/*
 * Base class for variable
 */
class _Variable: public _Term
{
protected:
    _Variable(std::string name);

    /* Globally unique variable name */
    std::string name;

    /* Count the number of variable use (in the term tree) */
    unsigned long uses;
};

/**
 * String term type
 */
class _StringTerm;
//using StringTerm = _StringTerm&;
typedef _StringTerm& StringTerm;

// Construction
StringTerm stringTerm(std::string&& str);

class _StringTerm: public _Term
{
public:
    virtual ~_StringTerm()
    {
    }
    ;

    /** Peek at native string value */
    virtual std::string& Unbox() const
    {
        throw std::runtime_error("Fatal error: cannot access unevaluated string value.");
    }
};
// _StringTerm

/**
 * String term value
 */
class _ValStringTerm: public _StringTerm
{
protected:
    /** The string value. A reference to it so that we can unbox it. */
    std::string& value;

public:
    _ValStringTerm(std::string& value);
    ~_ValStringTerm();

    Term Copy(Context c);
    std::string& Unbox() const;

};

/**
 * Variable of type String
 */
class Var_StringTerm: public _StringTerm, _Variable
{
public:
    Var_StringTerm(std::string& name);
};
typedef Var_StringTerm& VarStringTerm;

VarStringTerm var_StringTerm(std::string&& str);

// --- Numeric type (double)

class _DoubleTerm;
//using DoubleTerm = _DoubleTerm&;
typedef _DoubleTerm& DoubleTerm;

// Construction
DoubleTerm doubleTerm(double value);

class _DoubleTerm: public _Term
{
public:
    virtual ~_DoubleTerm()
    {
    }
    ;

    /** Peek at native double value */
    virtual double Unbox() const
    {
        throw std::runtime_error("Fatal error: cannot access unevaluated numeric value.");
    }
};
// _DoubleTerm

/**
 * String term value
 */
class _ValDoubleTerm: public _DoubleTerm
{
protected:
    /** The double value. */
    double value;

public:
    _ValDoubleTerm(double value);
    ~_ValDoubleTerm();

    Term Copy(Context c);
    double Unbox() const;

};

/*
 * Variable of type Numeric
 */
class Var_DoubleTerm: public _DoubleTerm, _Variable
{
public:
    Var_DoubleTerm(std::string& name);
};
typedef Var_DoubleTerm& VarDoubleTerm;

VarDoubleTerm var_DoubleTerm(double value);

//}// runtime
//} // ts
#endif
