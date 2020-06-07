drop table if exists barcodes;
drop table if exists variants;
drop table if exists products;
drop table if exists conversions;
drop table if exists locations;
drop table if exists units;

CREATE OR REPLACE FUNCTION mantain_updated_at()
RETURNS TRIGGER AS $$
BEGIN
   NEW.updated = now();
   RETURN NEW;
END;
$$ language 'plpgsql';

-- Change this to ensure location names are unique...
create table locations (
  id int generated by default as identity primary key,
  name text unique not null
);

create type si_dimension as enum ('mass', 'volume', 'length');

create table units (
  id int generated by default as identity primary key,
  singular text not null,
  plural text not null,
  si si_dimension default null
);

create table conversions (
  product int not null references products(id),
  a int not null references units(id),
  b int not null references units(id) constraint invalid_edge_b check (a <> b),
  a_amount int not null,
  b_amount int not null,
  id bigint unique GENERATED ALWAYS AS (case 
                                        when a >= b
                                        	then ((a * a) + a + b)
                                        	else ((b * b) + b + a)
                                        end)
                                        STORED,
  primary key (id)
);

create table products (
  id int generated by default as identity primary key,
  name text not null,
  location int references locations(id),
  unit int not null references units(id),
  created timestamp not null default current_timestamp,
  updated timestamp not null default current_timestamp
);

create trigger products_updated_at before update
on products for each row execute procedure 
mantain_updated_at();

create table variants (
  id int generated by default as identity primary key,
  product int references products(id),
  name text not null,
  calories real,
  unit int references units(id),
  created timestamp not null default current_timestamp,
  updated timestamp not null default current_timestamp
);

create trigger variants_updated_at before update
on variants for each row execute procedure 
mantain_updated_at();

create table barcodes (
  barcode text not null,
  variant int references variants(id)
);

insert into locations (name) values ('fridge'),('freezer');
insert into units (singular, plural, si) values
('g', 'g', 'mass'),
('ml', 'ml', 'volume'),
('mm', 'mm', 'distance'),
('onion', 'onions', null);
insert into products (name, location, unit) values
('onion', 1, 4),
('mayonaise', 2, 2);

insert into variants (product, name, calories, unit) values
(2, 'Heinz Mayonaise', 1.3, 6),
(2, 'Yo Sushi! Mayonaise', 1.6, 7),
(1, 'onion', 0.4, 4),
(1, 'onions (bag)', 0.4, 8);

-- Push the above varinants, then add conversions! (I hope you had a nice cuddle).
  -- convert_from int not null references units(id),
  -- convert_to int not null references units(id),
  -- factor real not null,

insert into conversions (convert_from, convert_to, factor) values
(1, 8, 4, 5),
(7, 2, 250),
(7, 1, 200),
(6, 2, 800),
(6, 1, 775);

-- variant_id int generated by default as identity primary key,
-- product int references products(product_id),
-- name text not null,
-- calories real,
-- default_unit unit,
-- discrete_unit int references discrete_units(id),
-- mass_to_volume real default null,
-- volume_to_length real default null,
-- length_to_mass real default null,