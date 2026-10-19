"""Deterministic runtime workloads and independent integer result oracles."""


def source(case):
    declarations = ''
    if case == 'integer_loop':
        body = 'total = (total * 7 + i % 13) % 10007;'
    elif case == 'branch_loop':
        body = ('if (i % 3 == 0) { total = (total + i) % 10007; } '
                'else { total = (total + 2) % 10007; }')
    elif case.startswith('call_'):
        count = int(case.split('_')[1])
        params = ', '.join(f'int p{i}' for i in range(count))
        arguments = ', '.join(['i', *map(str, range(1, count))][:count])
        result = '7' if count == 0 else '(p0 % 17 + 3)' if count == 1 else '(' + ' + '.join(f'p{i}' for i in range(count)) + ') % 97'
        declarations = f'int called({params}) {{ return {result}; }}\n'
        body = f'total = total + called({arguments});'
    elif case in ('object_fields', 'object_copy'):
        declarations = ('class Cell { int value; Cell(int n) { value = n; } '
                        'int read() { return value; } }\n')
        if case == 'object_fields':
            body = 'Cell cell(i % 17); cell.value = cell.value + 1; total = total + cell.read();'
        else:
            body = ('Cell original(i % 17); Cell copy = original; copy.value = copy.value + 1; '
                    'total = total + copy.value + original.value;')
    elif case in ('virtual_monomorphic', 'virtual_alternating'):
        declarations = ('class Base { int marker; virtual int value(int n) { return n % 17 + 1; } }\n'
                        'class First : Base { override int value(int n) { return n % 17 + 3; } }\n'
                        'class Second : Base { override int value(int n) { return n % 17 + 5; } }\n')
        before = 'Base* first = new First();'
        after = 'delete first;'
        body = 'total = total + first.value(i);'
        if case == 'virtual_alternating':
            before += ' Base* second = new Second();'
            after = 'delete second; ' + after
            body = ('Base* current = first; if (i % 2 != 0) { current = second; } '
                    'total = total + current.value(i);')
        return declarations + wrap(body, before, after)
    else:
        raise ValueError(f'Unknown benchmark case: {case}')
    return declarations + wrap(body)


def wrap(body, before='', after=''):
    return (f'int bench(int n):0002 {{ {before} int total = 0; int i = 0; '
            f'while (i < n) {{ {body} i = i + 1; }} {after} return total; }}\n'
            'int main() { return bench(512); }\n')


def expected(case, n):
    total = 0
    for i in range(n):
        if case == 'integer_loop':
            total = (total * 7 + i % 13) % 10007
        elif case == 'branch_loop':
            total = (total + (i if i % 3 == 0 else 2)) % 10007
        elif case.startswith('call_'):
            count = int(case.split('_')[1])
            total += 7 if count == 0 else i % 17 + 3 if count == 1 else (i + sum(range(1, count))) % 97
        elif case == 'object_fields':
            total += i % 17 + 1
        elif case == 'object_copy':
            total += 2 * (i % 17) + 1
        elif case == 'virtual_monomorphic':
            total += i % 17 + 3
        elif case == 'virtual_alternating':
            total += i % 17 + (3 if i % 2 == 0 else 5)
        else:
            raise ValueError(f'Unknown benchmark case: {case}')
    return total
