/*
 * Copyright (c) 2016 IBM Corporation. 
 *
 * Grammar for simple structured text with embedded terms.
 *
 */
grammar Text4;

text 
    : content text
    |
    ;

content 
    : CHARS
    | BREAK
    | OPENINDENT text CLOSEINDENT
    | CASTSTRING text 
    ;
    
OPENINDENT  : '\u27e6'; // ⟦
CLOSEINDENT : '\u27e7'; // ⟧
CASTSTRING  : '\u2020'; // †

         
CHARS : ~[\n\r\f\u00b6\u27e6\u27e7\u27e8\u2020\u00ab\u00bb⟨]+;
BREAK : [\n\r\f\u00b6]+;