// Parse Showdown TS data files to an AST with the TypeScript compiler API and keep literal values only.
import fs from 'node:fs';
import ts from 'typescript';
const K = ts.SyntaxKind;
function lit(n) {
  if (!n) return undefined;
  switch (n.kind) {
    case K.StringLiteral: case K.NoSubstitutionTemplateLiteral: return n.text;
    case K.NumericLiteral: return Number(n.text);
    case K.TrueKeyword: return true; case K.FalseKeyword: return false; case K.NullKeyword: return null;
    case K.PrefixUnaryExpression: { const v = lit(n.operand); if (typeof v !== 'number') return undefined; return n.operator === K.MinusToken ? -v : v; }
    case K.ArrayLiteralExpression: return n.elements.map(lit).filter(v => v !== undefined);
    case K.ObjectLiteralExpression: { const o = {}; for (const p of n.properties) { if (p.kind !== K.PropertyAssignment) continue; const k = p.name.text ?? p.name.getText(); const v = lit(p.initializer); if (v !== undefined) o[k] = v; } return o; }
    case K.AsExpression: case K.SatisfiesExpression: case K.ParenthesizedExpression: return lit(n.expression);
    case K.BinaryExpression: { const a = lit(n.left), b = lit(n.right); if (typeof a === 'number' && typeof b === 'number') { switch (n.operatorToken.kind) { case K.SlashToken: return a / b; case K.AsteriskToken: return a * b; case K.PlusToken: return a + b; case K.MinusToken: return a - b; } } return undefined; }
    default: return undefined;
  }
}
const [, , inp, out] = process.argv;
const sf = ts.createSourceFile(inp, fs.readFileSync(inp, 'utf8'), ts.ScriptTarget.Latest, true);
let res;
for (const st of sf.statements) if (st.kind === K.VariableStatement) for (const d of st.declarationList.declarations) { const v = lit(d.initializer); if (v && typeof v === 'object') res = v; }
fs.writeFileSync(out, JSON.stringify(res)); console.log(inp.split('/data/')[1], Object.keys(res).length);
